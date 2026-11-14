package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const proxyBuild = "go-range-20261114-r1"

var rangeRE = regexp.MustCompile(`^bytes=([0-9]+)-([0-9]*)$`)
var contentRangeRE = regexp.MustCompile(`^bytes ([0-9]+)-([0-9]+)/([0-9]+)$`)
var errNoRange = errors.New("upstream ignored Range")

type upstreamError struct {
	code         int
	contentRange string
}

func (e *upstreamError) Error() string { return fmt.Sprintf("upstream status %d", e.code) }

type Player struct {
	client      *http.Client
	header      http.Header
	rawRange    string
	start, end  int64
	thread      int
	chunkSize   int64
	url, method string
	rangeErr    error
}

func NewPlayer(header http.Header, thread, chunkSizeKB int, url string) *Player {
	h := http.Header{}
	for _, key := range []string{"User-Agent", "Cookie", "Referer", "Authorization", "If-Range"} {
		if v := header.Get(key); v != "" {
			h.Set(key, v)
		}
	}
	h.Set("Accept-Encoding", "identity")
	raw := strings.TrimSpace(header.Get("Range"))
	start, end, rangeErr := parseRange(raw)
	return &Player{client: &http.Client{Transport: &http.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		MaxIdleConns:    32, MaxIdleConnsPerHost: 16, IdleConnTimeout: 30 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		DialContext:           (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
	}}, header: h, rawRange: raw, start: start, end: end, rangeErr: rangeErr, thread: thread, chunkSize: int64(chunkSizeKB) * 1024, url: url, method: http.MethodGet}
}
func parseRange(raw string) (int64, int64, error) {
	if raw == "" {
		return 0, -1, nil
	}
	m := rangeRE.FindStringSubmatch(raw)
	if len(m) == 0 {
		return 0, -1, errors.New("non-single byte range")
	}
	start, err := strconv.ParseInt(m[1], 10, 64)
	if err != nil {
		return 0, -1, err
	}
	end := int64(-1)
	if m[2] != "" {
		end, err = strconv.ParseInt(m[2], 10, 64)
		if err != nil || end < start {
			return 0, -1, errors.New("invalid byte range")
		}
	}
	return start, end, nil
}
func parseContentRange(raw string) (int64, int64, int64, error) {
	m := contentRangeRE.FindStringSubmatch(raw)
	if len(m) == 0 {
		return 0, 0, 0, errors.New("missing Content-Range")
	}
	s, e, n := int64(0), int64(0), int64(0)
	values := []*int64{&s, &e, &n}
	for i, v := range values {
		var err error
		*v, err = strconv.ParseInt(m[i+1], 10, 64)
		if err != nil {
			return 0, 0, 0, err
		}
	}
	if s < 0 || e < s || n <= e {
		return 0, 0, 0, errors.New("invalid Content-Range")
	}
	return s, e, n, nil
}
func firstEnd(start, end, chunk int64) (int64, error) {
	const max = int64(1<<63 - 1)
	if start < 0 || start == max || chunk < 1 || (end >= 0 && end < start) {
		return 0, errors.New("invalid range bounds")
	}
	length := int64(100)
	if end >= 0 {
		length = end - start
		if length < chunk {
			length++
		} else {
			length = chunk
		}
	}
	length = min(length, chunk, max-start)
	return start + length, nil
}
func (p *Player) Play(w http.ResponseWriter, ctx context.Context) error {
	defer p.client.CloseIdleConnections()
	if p.method == http.MethodHead || p.rangeErr != nil {
		return p.stream(w, ctx, p.rawRange, -1, -1, -1, false)
	}
	probeEnd, err := firstEnd(p.start, p.end, p.chunkSize)
	if err != nil {
		return err
	}
	data, h, total, err := p.chunk(ctx, p.start, probeEnd)
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		log.Printf("[%s] initial range failed; trying original stream: %v", proxyBuild, err)
		return p.stream(w, ctx, p.rawRange, -1, -1, -1, false)
	}
	end := total - 1
	if p.end >= 0 {
		end = min(p.end, end)
	}
	length := end - p.start + 1
	copyHeaders(w.Header(), h)
	w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	w.Header().Set("Accept-Ranges", "bytes")
	status := http.StatusOK
	if p.rawRange != "" {
		status = http.StatusPartialContent
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", p.start, end, total))
	} else {
		w.Header().Del("Content-Range")
	}
	w.WriteHeader(status)
	if _, err = w.Write(data); err != nil {
		return err
	}
	flush(w)
	next := p.start + int64(len(data))
	for next <= end {
		if err = ctx.Err(); err != nil {
			return err
		}
		count := int(min(int64(p.thread), (end-next)/p.chunkSize+1))
		blocks := make([][]byte, count)
		failures := make([]error, count)
		var wg sync.WaitGroup
		for i := 0; i < count; i++ {
			s := next + int64(i)*p.chunkSize
			e := s + min(p.chunkSize, end-s+1)
			wg.Add(1)
			go func(i int, s, e int64) {
				defer wg.Done()
				var n int64
				blocks[i], _, n, failures[i] = p.chunk(ctx, s, e)
				if failures[i] == nil && n != total {
					failures[i] = errors.New("upstream file size changed")
				}
			}(i, s, e)
		}
		wg.Wait()
		for i := 0; i < count; i++ {
			if failures[i] != nil {
				log.Printf("[%s] chunk failed at %d; continuing single stream: %v", proxyBuild, next, failures[i])
				return p.stream(w, ctx, fmt.Sprintf("bytes=%d-%d", next, end), next, end, total, true)
			}
			if _, err = w.Write(blocks[i]); err != nil {
				return err
			}
			next += int64(len(blocks[i]))
			flush(w)
		}
	}
	return nil
}
func (p *Player) chunk(parent context.Context, start, end int64) ([]byte, http.Header, int64, error) {
	ctx, cancel := context.WithTimeout(parent, 60*time.Second)
	defer cancel()
	var last error
	for attempt := 0; attempt < 3; attempt++ {
		data, h, total, err := p.chunkOnce(ctx, start, end)
		if err == nil {
			return data, h, total, nil
		}
		last = err
		var status *upstreamError
		if errors.As(err, &status) && status.code < 500 && status.code != 429 {
			break
		}
		if errors.Is(err, errNoRange) {
			break
		}
		if attempt < 2 {
			select {
			case <-time.After(time.Duration(attempt+1) * 500 * time.Millisecond):
			case <-ctx.Done():
				return nil, nil, 0, ctx.Err()
			}
		}
	}
	return nil, nil, 0, last
}
func (p *Player) chunkOnce(ctx context.Context, start, end int64) ([]byte, http.Header, int64, error) {
	if start < 0 || end <= start || end-start > 4*1024*1024 {
		return nil, nil, 0, errors.New("invalid chunk size")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.url, nil)
	if err != nil {
		return nil, nil, 0, err
	}
	req.Header = p.header.Clone()
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end-1))
	resp, err := p.client.Do(req)
	if err != nil {
		return nil, nil, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == 200 {
		return nil, nil, 0, errNoRange
	}
	if resp.StatusCode != 206 {
		return nil, nil, 0, &upstreamError{resp.StatusCode, resp.Header.Get("Content-Range")}
	}
	s, e, total, err := parseContentRange(resp.Header.Get("Content-Range"))
	if err != nil {
		return nil, nil, 0, err
	}
	if s != start || e != min(end-1, total-1) {
		return nil, nil, 0, errors.New("Content-Range does not match requested bytes")
	}
	expected := e - s + 1
	data, err := io.ReadAll(io.LimitReader(resp.Body, expected+1))
	if err != nil {
		return nil, nil, 0, err
	}
	if int64(len(data)) != expected {
		return nil, nil, 0, errors.New("truncated or oversized chunk")
	}
	return data, resp.Header, total, nil
}

// Never append a whole-file 200 body to a partially sent video.
func (p *Player) stream(w http.ResponseWriter, ctx context.Context, raw string, start, end, expectedTotal int64, continuation bool) error {
	req, err := http.NewRequestWithContext(ctx, p.method, p.url, nil)
	if err != nil {
		return err
	}
	req.Header = p.header.Clone()
	if raw != "" {
		req.Header.Set("Range", raw)
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		return &upstreamError{resp.StatusCode, resp.Header.Get("Content-Range")}
	}
	expectedLength := resp.ContentLength
	if continuation {
		if resp.StatusCode != 206 {
			return errors.New("upstream ignored continuation Range")
		}
		s, e, total, err := parseContentRange(resp.Header.Get("Content-Range"))
		if err != nil {
			return err
		}
		if s != start || e != end || total != expectedTotal {
			return errors.New("upstream continuation bounds mismatch")
		}
	} else if p.rangeErr == nil && resp.StatusCode == 206 {
		s, e, total, err := parseContentRange(resp.Header.Get("Content-Range"))
		if err != nil {
			return err
		}
		expectedEnd := total - 1
		if p.end >= 0 {
			expectedEnd = min(p.end, expectedEnd)
		}
		if s != p.start || e != expectedEnd {
			return errors.New("upstream stream bounds mismatch")
		}
		expectedLength = e - s + 1
	}
	if p.method == http.MethodHead {
		copyHeaders(w.Header(), resp.Header)
		w.WriteHeader(resp.StatusCode)
		return nil
	}
	first := make([]byte, 1)
	if _, err = io.ReadFull(resp.Body, first); err != nil {
		return errors.New("empty upstream media response")
	}
	if !continuation {
		copyHeaders(w.Header(), resp.Header)
		if expectedLength >= 0 {
			w.Header().Set("Content-Length", strconv.FormatInt(expectedLength, 10))
		}
		w.WriteHeader(resp.StatusCode)
	}
	if _, err = w.Write(first); err != nil {
		return err
	}
	flush(w)
	if continuation {
		_, err = io.CopyN(w, resp.Body, end-start)
	} else if expectedLength > 0 {
		_, err = io.CopyN(w, resp.Body, expectedLength-1)
	} else {
		_, err = io.Copy(w, resp.Body)
	}
	return err
}
func copyHeaders(dest, source http.Header) {
	blocked := map[string]bool{"Connection": true, "Transfer-Encoding": true, "Keep-Alive": true, "Proxy-Connection": true, "Te": true, "Trailer": true, "Upgrade": true}
	for _, value := range source.Values("Connection") {
		for _, name := range strings.Split(value, ",") {
			blocked[http.CanonicalHeaderKey(strings.TrimSpace(name))] = true
		}
	}
	for k, v := range source {
		if !blocked[http.CanonicalHeaderKey(k)] {
			dest[k] = append([]string(nil), v...)
		}
	}
}
func flush(w http.ResponseWriter) {
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
}

type trackedResponse struct {
	http.ResponseWriter
	committed bool
}

func (w *trackedResponse) WriteHeader(status int) {
	w.committed = true
	w.ResponseWriter.WriteHeader(status)
}
func (w *trackedResponse) Write(p []byte) (int, error) {
	w.committed = true
	return w.ResponseWriter.Write(p)
}
func (w *trackedResponse) Flush() { w.committed = true; flush(w.ResponseWriter) }
func serveProxy(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "Method not allowed", 405)
		return
	}
	q := r.URL.Query()
	target, urlErr := url.Parse(q.Get("url"))
	t, e1 := strconv.Atoi(q.Get("thread"))
	c, e2 := strconv.Atoi(q.Get("chunkSize"))
	if e1 != nil || e2 != nil || t < 1 || t > 16 || c < 1 || c > 4096 || urlErr != nil || target.Host == "" || (target.Scheme != "http" && target.Scheme != "https") {
		http.Error(w, "Invalid proxy parameters", 400)
		return
	}
	p := NewPlayer(r.Header, t, c, q.Get("url"))
	p.method = r.Method
	tracked := &trackedResponse{ResponseWriter: w}
	if err := p.Play(tracked, r.Context()); err != nil {
		log.Printf("[%s] playback failed: %v", proxyBuild, err)
		if tracked.committed {
			panic(http.ErrAbortHandler)
		}
		status := http.StatusBadGateway
		var up *upstreamError
		if errors.As(err, &up) {
			if up.code >= 400 {
				status = up.code
			}
			if up.contentRange != "" {
				w.Header().Set("Content-Range", up.contentRange)
			}
		}
		http.Error(w, http.StatusText(status), status)
	}
}

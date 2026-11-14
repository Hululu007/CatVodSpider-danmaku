package main

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func media() []byte {
	b := make([]byte, 8193)
	for i := range b {
		b[i] = byte(i*37 + i/251)
	}
	return b
}
func sendRange(w http.ResponseWriter, r *http.Request, data []byte) {
	if r.Header.Get("Range") == "" {
		w.Write(data)
		return
	}
	s, e, err := parseRange(r.Header.Get("Range"))
	if err != nil || s >= int64(len(data)) {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", len(data)))
		w.WriteHeader(416)
		return
	}
	if e < 0 || e >= int64(len(data)) {
		e = int64(len(data)) - 1
	}
	w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", s, e, len(data)))
	w.Header().Set("Content-Length", strconv.FormatInt(e-s+1, 10))
	w.WriteHeader(206)
	w.Write(data[s : e+1])
}
func proxyFor(t *testing.T, upstream http.HandlerFunc, raw string) (*http.Response, error) {
	t.Helper()
	up := httptest.NewServer(upstream)
	t.Cleanup(up.Close)
	local := httptest.NewServer(http.HandlerFunc(serveProxy))
	t.Cleanup(local.Close)
	req, _ := http.NewRequest("GET", local.URL+"?thread=4&chunkSize=1&url="+url.QueryEscape(up.URL), nil)
	req.Header.Set("Range", raw)
	req.Header.Set("Cookie", "authorized-test-cookie")
	return local.Client().Do(req)
}
func TestRangeBoundsAndBytes(t *testing.T) {
	for _, tc := range []struct {
		raw                string
		start, end, status int
	}{
		{"", 0, 8192, 200}, {"bytes=0-0", 0, 0, 206}, {"bytes=1000-1010", 1000, 1010, 206},
		{"bytes=8170-99999", 8170, 8192, 206}, {"bytes=2048-", 2048, 8192, 206},
		{"bytes=0-65535", 0, 8192, 206}, {"bytes=1000-5000", 1000, 5000, 206},
	} {
		t.Run(tc.raw, func(t *testing.T) {
			data := media()
			resp, err := proxyFor(t, func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Cookie") != "authorized-test-cookie" {
					t.Error("Cookie lost")
				}
				sendRange(w, r, data)
			}, tc.raw)
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			got, err := io.ReadAll(resp.Body)
			if err != nil {
				t.Fatal(err)
			}
			if resp.StatusCode != tc.status || !bytes.Equal(got, data[tc.start:tc.end+1]) {
				t.Fatalf("status=%d bytes=%d", resp.StatusCode, len(got))
			}
			if resp.ContentLength != int64(tc.end-tc.start+1) {
				t.Fatalf("length=%d", resp.ContentLength)
			}
		})
	}
}
func TestInitialFailuresAreNotEmptySuccess(t *testing.T) {
	for _, status := range []int{416, 403, 200, 204} {
		t.Run(strconv.Itoa(status), func(t *testing.T) {
			resp, err := proxyFor(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Range", "bytes */8193")
				w.WriteHeader(status)
			}, "bytes=0-65535")
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			io.ReadAll(resp.Body)
			if resp.StatusCode == 200 || resp.StatusCode == 206 {
				t.Fatalf("false media success: %d", resp.StatusCode)
			}
			if status == 416 && resp.Header.Get("Content-Range") != "bytes */8193" {
				t.Error("416 size lost")
			}
		})
	}
}
func TestIgnoredRangeStreamsExactlyOnce(t *testing.T) {
	data := media()
	resp, err := proxyFor(t, func(w http.ResponseWriter, r *http.Request) { w.Write(data) }, "bytes=1000-")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	got, err := io.ReadAll(resp.Body)
	if err != nil || resp.StatusCode != 200 || !bytes.Equal(data, got) {
		t.Fatalf("invalid 200 fallback: bytes=%d error=%v", len(got), err)
	}
}
func TestContinuationExactBytes(t *testing.T) {
	data := media()
	var seen atomic.Bool
	resp, err := proxyFor(t, func(w http.ResponseWriter, r *http.Request) {
		raw := r.Header.Get("Range")
		if raw == "bytes=2048-3071" {
			w.WriteHeader(416)
			return
		}
		if raw == "bytes=2048-5000" {
			seen.Store(true)
		}
		sendRange(w, r, data)
	}, "bytes=0-5000")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	got, err := io.ReadAll(resp.Body)
	if err != nil || !seen.Load() || !bytes.Equal(got, data[:5001]) {
		t.Fatalf("bad continuation: seen=%v bytes=%d err=%v", seen.Load(), len(got), err)
	}
}
func TestBadContinuationAbortsResponse(t *testing.T) {
	for _, kind := range []string{"ignored", "changed-total", "truncated"} {
		t.Run(kind, func(t *testing.T) {
			data := media()
			resp, err := proxyFor(t, func(w http.ResponseWriter, r *http.Request) {
				raw := r.Header.Get("Range")
				if raw == "bytes=2048-3071" {
					w.WriteHeader(416)
					return
				}
				if raw == "bytes=2048-5000" {
					if kind == "ignored" {
						w.Write(data)
						return
					}
					n := len(data)
					if kind == "changed-total" {
						n++
					}
					w.Header().Set("Content-Range", fmt.Sprintf("bytes 2048-5000/%d", n))
					w.Header().Set("Content-Length", "2953")
					w.WriteHeader(206)
					if kind == "truncated" {
						w.Write(data[2048:2058])
					} else {
						w.Write(data[2048:5001])
					}
					return
				}
				sendRange(w, r, data)
			}, "bytes=0-5000")
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			got, err := io.ReadAll(resp.Body)
			if err == nil || len(got) >= 5001 {
				t.Fatalf("truncated video treated as complete: bytes=%d err=%v", len(got), err)
			}
		})
	}
}
func TestTruncatedChunkRejected(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Range", "bytes 0-10/100")
		w.WriteHeader(206)
		w.Write([]byte("short"))
	}))
	defer up.Close()
	p := NewPlayer(http.Header{}, 2, 1, up.URL)
	defer p.client.CloseIdleConnections()
	if _, _, _, err := p.chunkOnce(context.Background(), 0, 11); err == nil {
		t.Fatal("accepted truncated chunk")
	}
}
func TestConcurrentDownloads(t *testing.T) {
	var active, maxActive atomic.Int32
	data := media()
	resp, err := proxyFor(t, func(w http.ResponseWriter, r *http.Request) {
		n := active.Add(1)
		defer active.Add(-1)
		for {
			old := maxActive.Load()
			if n <= old || maxActive.CompareAndSwap(old, n) {
				break
			}
		}
		time.Sleep(10 * time.Millisecond)
		sendRange(w, r, data)
	}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)
	if maxActive.Load() < 2 {
		t.Fatal("multi-thread downloading lost")
	}
}
func TestCancellationStopsRequest(t *testing.T) {
	started := make(chan struct{})
	stopped := make(chan struct{})
	var once sync.Once
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		once.Do(func() { close(started) })
		<-r.Context().Done()
		close(stopped)
	}))
	defer up.Close()
	p := NewPlayer(http.Header{}, 2, 1, up.URL)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- p.Play(httptest.NewRecorder(), ctx) }()
	<-started
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancel ignored")
		}
	case <-time.After(time.Second):
		t.Fatal("cancel timeout")
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("upstream not cancelled")
	}
}
func TestRangeParsing(t *testing.T) {
	for _, raw := range []string{"bytes=-10", "bytes=0-1,3-4", "bytes=2-1", "bytes=9223372036854775808-", "bytes=0-1junk"} {
		if _, _, err := parseRange(raw); err == nil {
			t.Fatalf("invalid accepted: %s", raw)
		}
	}
	if e, err := firstEnd(1000, 1010, 1024); err != nil || e != 1011 {
		t.Fatalf("firstEnd=%d %v", e, err)
	}
	if e, err := firstEnd(0, 0, 1024); err != nil || e != 1 {
		t.Fatalf("single-byte firstEnd=%d %v", e, err)
	}
}
func TestHeadAndParameterValidation(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Header().Set("Content-Length", "8193") }))
	defer up.Close()
	req := httptest.NewRequest("HEAD", "/?thread=2&chunkSize=1&url="+url.QueryEscape(up.URL), nil)
	w := httptest.NewRecorder()
	serveProxy(w, req)
	if w.Code != 200 || w.Body.Len() != 0 || w.Header().Get("Content-Length") != "8193" {
		t.Fatal("bad HEAD")
	}
	for _, raw := range []string{"file:///etc/passwd", "http://", ""} {
		w := httptest.NewRecorder()
		serveProxy(w, httptest.NewRequest("GET", "/?thread=2&chunkSize=1&url="+url.QueryEscape(raw), nil))
		if w.Code != 400 {
			t.Fatalf("bad URL accepted: %s", raw)
		}
	}
}
func TestHopHeadersRemoved(t *testing.T) {
	src := http.Header{"Connection": {"X-Internal"}, "X-Internal": {"secret"}, "Content-Type": {"video/mp4"}}
	dst := http.Header{}
	copyHeaders(dst, src)
	if strings.Contains(fmt.Sprint(dst), "secret") || dst.Get("Content-Type") != "video/mp4" {
		t.Fatal("header filtering")
	}
}

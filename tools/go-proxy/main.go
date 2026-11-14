// 独立运行模式下的入口。
// 这个文件用于直接启动一个本地 HTTP 代理服务，便于单独调试 Go 代理逻辑。
package main

import (
	_ "embed"
	"fmt"
	"log"
	"net/http"
	"os"
	"time"
)

//go:embed LICENSE
var licenseText string

func main() {
	http.HandleFunc("/license", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprint(w, licenseText)
	})
	// 根路径用于最简单的存活探测。
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "ok")
	})

	// /proxy 是核心代理入口，必须携带线程数、分块大小和目标地址。
	http.HandleFunc("/proxy", serveProxy)

	// 健康检查接口，方便上层轮询代理是否正常可用。
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"status": "healthy", "type": "go", "port": %d, "build": "%s", "timestamp": "%s"}`, 5575, proxyBuild, time.Now().Format(time.RFC3339))
	})

	log.SetOutput(os.Stdout)
	log.Printf("服务器启动在 :5575 BUILD=%s", proxyBuild)
	log.Fatal(http.ListenAndServe(":5575", nil))
}

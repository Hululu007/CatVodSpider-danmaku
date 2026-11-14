# Go 代理 Range 修复

源码基于 https://github.com/Silent1566/GoProxyAndroid 的提交
`aca8a796f436e8b92288316266327daacfa14de6`，保留 MIT 许可。
当前目录构建独立 Android 二进制；JNI 入口不包含在此构建中。

修复首块结束位置、end=0、文件末尾分片、Content-Length、上游错误为空200，
并在分片失败时从尚未发送的字节位置回退连续 Range 传输。
不允许把忽略 Range 的完整200响应拼入已经输出的局部视频。

运行 `go test ./...` 和 `go vet ./...` 验证。主机具备 C 编译器时可运行 `go test -race ./...`。
`build.ps1 -NdkRoot <NDK目录> -OutputDirectory <产物目录>`
交叉构建 ARM/ARM64 到指定输出目录；验证后由主项目打包脚本包含。
固定 Go 1.25.5，与原代理版本一致；使用 Android API 24 的 NDK 编译器。

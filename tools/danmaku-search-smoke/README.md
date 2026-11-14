# 弹幕搜索回归

在本机 JVM 上直接编译并执行生产 `LeoDanmakuService`、`MediaIdentityResolver`、`DanmakuManager` 等类。Android UI 和 HTTP 请求以测试替身隔离，不连接播放器、不推送弹幕。

要求：Python 3、JDK 17（`java`/`javac` 在 PATH）、本机 Android SDK 36。首次从 Maven Central 下载 JSON-java 20240303 到项目 `build/`。

```powershell
python tools/danmaku-search-smoke/check.py
```

覆盖不可见字符、Unicode 空格、关键词快照及别名、API 错集后查全集、错集/未知集数不推送、采访/预告排除、手动结果保留、年份约束、无集数电影，以及现有季集解析样例。

同时覆盖 DTS/DDP/TrueHD/AAC 声道、位深、采样率、帧率和音轨数量，避免电影文件参数被误判为集数。可传入实际电影搜索响应，验证完整身份解析到自动匹配流程：

```powershell
python tools/danmaku-search-smoke/check.py --movie-fixture <电影搜索响应.json>
```

该样例使用《空枪》元数据及其搜索结果，预期无集数查询并选中爱奇艺正片 `193886`。

`--media-fixture <目录>` 可读取 `current-title.txt`、`current-artist.txt`、`current-url.txt` 三个 UTF-8 文件，连同播放地址一起验证身份解析。URL 只允许真实视频文件名或完整显式集数作为回退；代理资源 ID 和分片编号不能变成电影集数。录制的私有播放地址只保存在忽略目录内。

可选地传入从实际 API 保存的 UTF-8 响应目录，其中 `episode2.json` 为《尚公主》带集数查询响应，`all.json` 为不带集数的全集响应：

```powershell
python tools/danmaku-search-smoke/check.py --live-fixtures build/danmaku-search-20261110
```

该录制样例预期选中正片第 2 集 `193497`，不能选到第 1 集或“采访2”。录制响应、依赖和编译产物均保留在忽略的 `build/` 目录。

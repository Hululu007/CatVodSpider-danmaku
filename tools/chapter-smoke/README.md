# 章节、焦点与弹幕关态检查

运行 build.ps1 -OutputName r10 构建独立 com.github.catvod.chaptercheck。复用生产 ChapterManager、FongMiNativeHelper、ChapterDialogFocus、HostDanmakuGate、Config/Manager，网络及History隔离，不操作影视真实数据。

2026-11-07 22:45:23，Android7.1.2 TV独立包104项通过，PID31006。日志：build/chapter-smoke/r10/android7-tv-results.txt。测试包已卸载。

覆盖当前r10规则：片头与片尾各成功一次，倒回反复轮询不重跳，两侧任意先后顺序独立；手动seek不暂停、不重置资格；Auto开关、清除手动值、供应商数据刷新不重置已完成动作；失败保留资格并遵守3秒重试间隔；旧会话晚到成功不污染新会话、主线程拒绝旧会话指令；退出/重进/换集/新真实源重置，元数据兜底不重置，新会话初始10秒保护保留。用重置测试时钟模拟等待，不需要实际等待10秒/3秒。

原生关闭备份/恢复/清除、自动章节分离/旧值迁移、晚到异集标记丢弃、有效子行焦点恢复、弹幕宿主关态/全部源取消选择等回归继续通过。

r8/r9的九秒阈值及10分钟暂停检查已被当前一次规则替换。此独立包未在平板上运行，独立检查不等于真实影视宿主验收。r10还需用户升级JAR后核对。JAR不包含这些测试文件。

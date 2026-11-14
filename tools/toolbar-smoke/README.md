# Android 原生布局运行检查

测试类在独立包 com.github.catvod.toolbarcheck 中构造 T1/T2/T3 三种结构，复用生产 ToolbarHostMap、ToolbarTheme、PlayerToolbarController；其余插件类用最小桩隔离。r7起T3使用归档宿主APK里的真实LinearLayoutCompat容器类，T1/T2保持系统布局模拟。

这是 Android 上的结构和操作保留测试，不是真宿主兼容验收。

首版 Android 10 手机运行通过 119 项：三端结构/同名节点区分、九入口、原控件身份与 tag、触摸焦点属性、动态倍速、原点击与长按、自定义方向键、原生标记控件、面板反复开关、显隐与恢复。测试完成后已卸载临时包。最新结果见文末r7。

资源使用 ids.xml；通过 Android SDK aapt、javac、d8、zipalign、apksigner 构建；测试 APK 和结果日志保存在 build/toolbar-smoke，不进入生产 JAR。首版Android 12 平板拒绝安装，未运行；当时TV未连接。

2026-11-07 r2：使用 build.ps1 -OutputName r2-final 构建，加入真实Config/ConfigManager及迁移测试；Android10上最终208项通过，包括时间轴坐标、四列、十入口、比例顺序、轨道直达、二维码与回退。记录见 build/toolbar-smoke/r2-final/android10-results.txt。独立包已卸载。

2026-11-07 r3：Android7.1.2 TV盒子运行独立包，模拟T1/T2/T3共670项通过，日志 build/toolbar-smoke/r3/android7-tv-results.txt（最后一轮PID24272）。包含9按钮外框、INVISIBLE零宽、居中、时间轴上移且开关面板固定、方向键和Tab事件圈定、返回关闭与源焦点、每次唤栏落下一集。未替代三端真实宿主升级验收。临时包已卸载；HTML三视口首焦点、Tab限制、关闭回归、时间轴相对播放区坐标以及TV唤栏落下一集通过，记录 html-results.json。

2026-11-07 r4：针对T1原生episodes在遥控模式下每次唤栏被设GONE的问题，新增稳定的选集入口，委托原控件performClick/performLongClick；原控件ID、父级恢复、监听、enabled和宿主visibility状态保留，T2/T3继续原控件。测试模拟原生选集初始GONE、后续INVISIBLE、隐藏状态点击、disabled同步及恢复。Android7.1.2 TV盒子独立测试677项通过（18:39，PID10814），日志 build/toolbar-smoke/r4/android7-tv-results.txt；临时包已卸载，真实宿主仍需手动升级后验收。

2026-11-07 r5：Android7.1.2 TV盒子模拟T1/T2/T3最终698项通过（18:44，PID24305，build/toolbar-smoke/r5/android7-tv-results.txt）。时间轴上下键通过Instrumentation注入到真实Android事件循环验证；直接调用Activity.dispatchKeyEvent不执行ViewRoot的兜底焦点导航，因此首轮测试方法已纠正。新增搜索方法调用前宿主control必须隐藏、框关闭、再次唤栏可见断言。HTML三视口搜索隐藏功能栏和TV时间轴上下回下一集通过，无脚本错误。临时包已卸载。

2026-11-07 r6：Android7.1.2 TV盒子独立测试模拟三端，最终776项通过（18:57，PID22902，build/toolbar-smoke/r6/android7-tv-results.txt）。增加真实AlertDialog确认/取消两条生命周期测试，模拟宿主在对话框关闭时重显top/titleLayout/clock，窗口焦点返回后及下一帧前清理，随后用户再次唤栏不受影响。音视频面板只有两列，宽度按两格实际内容计算（TV226dp、手机平板211dp），不保留空格。HTML三端选中/取消后顶栏与功能栏隐藏、音视频宽度通过，无JS错误。临时包已卸载。测试使用宿主结构模拟及原生对话框，不等于宿主APK升级后的完整实测。

2026-11-07 r7：平板实机显示T3的bottom/按钮行为LinearLayoutCompat，先前系统LinearLayout模拟未覆盖，生产映射因此误回退。build.ps1现将device_inspect/toolbar/T3/host.apk放入独立APK资产，由DexClassLoader加载真实容器及LayoutParams类；新增实际类不是系统LinearLayout、旧UI恢复原参数对象的检查。Android7.1.2 TV盒子最终828项通过（19:10:28，PID17475），日志build/toolbar-smoke/r7/android7-tv-results.txt；检查包已卸载。构建使用build.ps1 -OutputName r7，首次加载宿主DEX约需30秒；等待ALL PASS或FAIL日志再判结果。约92MB宿主APK只用于检查包，不进入custom_spider.jar。平板未安装此检查包，也尚未完成r7在真实宿主里的升级验收。

2026-11-07 r8-final：清除头尾紧接原生片尾，委托清除方法且不触发原标记点击；不可用宿主章节移除网格槽，重新可用时恢复；原监听和焦点限制保留。最终三端876项通过（TV独立包，T3真实容器类），日志build/toolbar-smoke/r8-final/android7-tv-results.txt。Auto业务及当前播放会话期限由tools/chapter-smoke的66项检查覆盖。

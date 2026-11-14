# 播放器 UI 原生首版实施记录

日期：2026-11-07。当前修订为 r8，见文末；以下首版记录保留历史。状态：可编译的实现与试用 JAR；**未达到全界面 1:1 和三端最终验收**。

## 本次已落实

- HTML 去掉音量条/静音按钮，弹幕搜索按原 Aurora 样式示意，章节改回右侧列表。
- 原生玻璃主栏按九入口组织；播放设置、弹幕、音视频采用新面板。
- T1/T2/T3 映射进入 ToolbarHostMap.java；限定 control→action 行，防止同名 player/video/text/danmaku 误命中。
- 搜索和章节直接调用原 JAR 方法；DanmakuUIHelper.java 完全未修改（SHA-256：73F493CC362E6302AFFFDC97607DA699C9AEEBDDC902408CA2B22ED8F8D0B4B6）。
- 保留原 TextView/CustomUpDownView 实例、监听器、ID、轨道 tag 3/1/2、原动态文本和触摸焦点属性。没有 Window.Callback 替换、全局按键/触摸拦截和自制音量控件。
- T1 延续显式横向循环焦点方式，将新位置接入；原上下键处理保持。手机/平板不强制套 TV 首焦点。
- 模式互斥、退出清理、换集关闭新面板、失效结构回退和本机恢复开关。

## 三端实际映射

| 项目 | T1 TV 5.1.6/516 | T2 手机 5.1.6/516 | T3 平板5.5.6/556 |
|---|---|---|---|
| control | LinearLayout | RelativeLayout | RelativeLayout |
| 按钮行 | actionLayout→原HorizontalScrollView | bottom→action→LinearLayout | bottom(LinearLayoutCompat)→action→LinearLayoutCompat，r7补齐支持 |
| 底部弹幕 | danmu | danmaku | danmaku，仅底部行 |
| 循环 | loop | loop | repeat |
| 原宿主章节 | title | title | chapter |
| 上下集 | 原行实例 | 原中央实例保持，入口委托 | 原中央实例保持，入口委托 |
| 轨道 | text/audio/video，保留3/1/2 tag | 同左 | 同左 |
| 特有项 | 条件episodes/title保留 | 原parse列表保持 | replay/parse/edition保留 |
| 插件章节 | showChapterDialog | 同左 | 同左 |

T3 同名侧边 danmaku 图标保持原位，不作为底部映射对象。未知版本/多义控制树/未识别交互槽位不接管。

## 和 HTML 的已知差异与未完成项

1. 选集、字幕、倍速仍由原控件打开原宿主菜单；这些选择菜单尚未实现 HTML 中的新玻璃外观。播放设置中的真实内核/解码/比例也是复用宿主选择或循环行为，没有把 HTML 示例候选硬编码。
2. 弹幕的不透明度/密度沿原宿主菜单处理，尚未移植 HTML 的独立滑块。
3. 手动片头/片尾和原宿主章节收纳在播放设置中，复用原控件；插件章节保持原样。
4. T2/T3 中央上下集仍保留原位，因此有重复入口，待真机确认新入口与焦点/手势后再决定是否隐藏。
5. 当前为程序化基础玻璃；未实现实时视频背景模糊。顶部、进度区域保留宿主布局，与 HTML 排布并非逐像素一致。
6. 还需三端真实播放页的全部按键/长按/触摸/IME/显隐/换源/旋转回归；原生控件换父级后的弹窗锚点和宿主对原行的遍历行为尤其需要实测。
7. 原探针完整节流整改与所有动态采集尚未完成。最终视觉/操作验收未通过前不默认启用新模式。

这些差异仍属于后续实施工作，不能因生成首版包而勾选“1:1完成”。

## 验证记录

- assembleRelease 通过，最终 DEX 类与四个必要条目检查通过。
- HTML 三端搜索、来源/排序、章节、Auto、跳转验证通过，无 JS 运行错误。
- Android 10 独立检查包：模拟 T1/T2/T3 共119项通过。日志：build/toolbar-smoke/android10-results.txt。
- 手机真实宿主：首版包已下载，缓存 MD5 对上；新版/旧版局域网切换接口各返回200。尚无有效 T2 bound 与前台操作通过证据。
- 手机随后锁屏；自动审批拒绝唤醒/解锁及继续播放命令（blocked by policy），前台验收暂停。
- 平板拒绝安装临时检查包（INSTALL_FAILED_USER_RESTRICTED），未进行运行检查。TV未连接。
- 临时检查 APK 已卸载；手机原订阅偏好和弹幕配置已逐字节恢复并校验MD5；测试服务器与ADB端口转发已关闭。新JAR临时缓存及配置历史可能仍由宿主管理。

## 启用与恢复

加载新 JAR 后进入原弹幕设置→远程搜索/输入→局域网页面→播放器功能栏，选择“使用新版功能栏”。也可在 JAR 站点 ext JSON 中设置 playerToolbarMode=custom；默认 legacy。

局域网页面的“恢复旧版功能栏”保存在独立本机偏好中，优先级高于订阅 ext，便于异常恢复。主栏显示条件仍由宿主 control/action 控制，详情页原 action 隐藏时不强行展示。

本次仅本地生成与手机临时验证，没有上传生产服务器或GitHub。index.js已同步新包MD5，后续部署必须成对上传并验证/tv；未核对当前线上/app/index.js逐字节基线，不应直接覆盖线上文件。

## 文件

- app/src/main/java/com/github/catvod/spider/ToolbarHostMap.java
- app/src/main/java/com/github/catvod/spider/ToolbarTheme.java
- app/src/main/java/com/github/catvod/spider/PlayerToolbarController.java
- DanmakuScanner.java / Init.java / WebServer.java：接入、生命周期与本机切换
- tools/toolbar-smoke：独立Android结构测试
- jar/custom_spider.jar及其MD5；D:/SI/影视jar/index.js两处MD5

最终本地 JAR MD5：3dceec8d07eb22c2a7f1b83aea0bf8c5；大小：5694331 字节。index.js 两处 MD5 已同步，node --check通过。119项Android检查发生在最后两项保护修改前；最终保护修改重新编译通过，尚未重跑设备检查。

## 17:49 手机手动升级后的复核

- 用户手动升级成功：手机缓存 JAR 的 MD5 为 `3dceec8d07eb22c2a7f1b83aea0bf8c5`，与本地交付包一致；前台为 VideoActivity。
- “升级后界面没有变化”的直接原因：`danmaku_config` 中仍为 `playerToolbarMode=legacy`，且不存在本机新版开关。升级文件不会自动切换旧配置。
- 已通过插件自身 `/save-toolbar-settings` 接口保存 `mode=custom`，HTTP 200；独立本机偏好读回为 custom。
- 17:49:43 日志确认 `aurora-toolbar-20261107-r1 bound T2`，映射包含 episodes/player/decode/scale/speed/reset/text/audio/video/opening/ending/danmaku/repeat/nativeChapter/prev/next。证据：`build/toolbar-smoke/phone-real-host-bound.txt`。
- 本次保留 custom 供用户检查，没有替换订阅、重新打包或创建端口转发。控制栏隐藏时 UI XML 仅见视频区，尚不能由此确认视觉、触摸与焦点全部通过。

## r2 修订（18:09，历史交付）

- 默认使用 custom；旧默认 legacy 配置执行一次迁移，保留显式本机回退和诊断模式。
- 面板挂在最近的 FrameLayout 独立浮层，定位于原 bottom/control 上方，不再插入原纵向布局，开关面板不改变时间轴位置。
- 播放设置与弹幕使用四列小按钮；每格触摸高度48dp，不足四项留空位。
- 主栏弹幕和面板内弹幕搜索恢复长按原二维码弹窗，同时提供扫码配置入口。宿主原弹幕控件点击/长按监听保持。
- 音视频面板直接呈现原音轨/视轨按钮，点击直接打开宿主选择器，不再先切Tab再点同名按钮。
- 原画面比例控件移到章节之后；主栏变为十入口，播放设置不再重复包含比例。动态文本、原点击和长按保留。
- 搜索/章节原弹窗源文件保持不变；HTML同步四列布局、轨道直达与比例位置。

验证：assembleRelease通过；最终生产代码在Android10独立检查包中模拟三端，208项通过（含新默认、一次迁移、回退、固定时间轴坐标、四列、轨道直接点击、二维码长按）。日志 build/toolbar-smoke/r2-final/android10-results.txt。测试包已卸载。HTML三视口交互检查通过，无脚本错误。

产物：jar/custom_spider.jar，5694623字节，MD5 `ddb59eb2d83aced1982de134063cb02a`；本地 index.js 两处MD5同步，node --check通过；DEX构建标识、迁移标识和两种ABI资产核验通过。未上传线上。

18:09设备复核仍加载 r1（MD5 3dceec8d07eb22c2a7f1b83aea0bf8c5），所以用户此时仍看到旧时间轴行为。r2尚需用户手动替换并冷重启后在真实宿主中验收；三端独立测试不等于三端真实宿主通过。全界面1:1其他缺项仍按首版记录待办。

## r3 修订（18:33，历史交付）

- TV原生seek整体移到主栏上方；设置面板继续独立浮层，开关不改原control高度。
- 外框按实际可见按钮和屏幕宽度收边居中，覆盖9按钮及动态隐藏；清除原minWidth/fillViewport，INVISIBLE控件压为零宽，窄屏保留横向滚动。主栏仍包含章节后的原画面比例，无需为凑9个删除功能。
- TV每次呼出主栏都落下一集；下一集不可用才回退播放设置。关闭新设置框时回该框对应入口。手机/平板触摸不强制主栏焦点。
- 弹幕面板顺序：弹幕搜索、弹幕、工具设置、扫码配置。打开首焦点为弹幕搜索。
- 新设置框用局部方向键/Tab导航与焦点范围限制，防原控件上下回调把焦点送回时间轴；点击、长按原监听保留，主栏原上下操作保留。返回/关闭释放限制；搜索、章节与二维码打开前先关闭新面板，原系统选择器使用自身窗口焦点。
- 标题更新从自动弹幕查询条件前独立执行，暂停也能解析；TV控件ContextWrapper解包Activity；标题位置跟随外框尺寸变化。元数据网络失败/异常30秒后重试，异步结果继续校验媒体签名。仍只在TMDB确认tv时显示，电影不显示。

验证：assembleRelease和git diff --check通过；Android7.1.2 TV盒子上的独立包模拟T1/T2/T3，最终670项通过（18:32，PID24272，build/toolbar-smoke/r3/android7-tv-results.txt），包括真实按键dispatch、返回关闭、9个可见入口/INVISIBLE零宽、自适应居中、时间轴固定、唤栏下一集。测试包已卸载。HTML三视口交互、时间轴相对播放区固定、首焦点/Tab范围/关闭回归及TV唤栏下一集通过，无脚本错误。

真实宿主边界：TV已连接192.168.31.41:5555，OK TV 5.1.6/516，播放“蒸蒸日上”第02集；尚未手动升级r3，不能将独立包通过视为宿主标题和遥控完整验收。手机此前仍加载r1且熄屏；平板此前拒绝测试包安装。TMDB该剧匹配与最终标题显示、原生选择器弹窗返回尚待r3真实宿主验收。

产物：jar/custom_spider.jar，5696861字节，MD5 `bc60d7a48909c4ae14f601a04b46f829`，构建标识 `aurora-toolbar-20261107-r3`。本地D:/SI/影视jar/index.js两处MD5已同步，node --check通过。未上传线上或GitHub。DanmakuUIHelper.java SHA256仍为73F493CC362E6302AFFFDC97607DA699C9AEEBDDC902408CA2B22ED8F8D0B4B6，原弹窗样式未改。

## r4/r5 修订（18:45，历史交付）

- 选集消失根因：T1原生VideoActivity.Y0每次呼出时根据遥控器偏好把episodes设为GONE；D01原始记录已存在此状态。新版T1用稳定入口委托原episodes点击，保留原ID、监听与宿主显隐/禁用状态，T2/T3仍用原控件。外框按实际入口收边，不为9个数量删除功能。
- 时间轴上下键：原timeBar的nextDown指向自己，所以向下不离开时间轴。r5将上下方向都接到下一集（不可用时播放设置），保留左右调进度。退出新版时恢复原方向ID。
- 弹幕搜索：先关闭新面板并将宿主control设GONE，再调用原showSearchDialog，搜索框出现时功能栏和时间轴已经隐藏。未修改原搜索/章节/二维码样式与输入逻辑，之后仍由宿主控制唤栏。

验证：assembleRelease、git diff --check通过；r4独立测试677项，r5最终698项（Android7.1.2 TV盒子，模拟T1/T2/T3，18:44 PID24305）。时间轴按键通过Instrumentation注入Android事件循环确认，不仅检查方向ID。初轮直接调用Activity.dispatchKeyEvent未走ViewRoot焦点导航，测试方式已纠正。搜索测试确认原搜索方法执行前control已隐藏。HTML三视口搜索/时间轴检查通过、无脚本错误。临时检查包已卸载。

真实宿主尚需手动升级r5复核；本轮UI XML因宿主无法进入idle而未获得可用截图/节点证据，不能宣称真宿主已全部验收。r4仅中间构建（MD5 6122cdceec4aa8802695ff14942a7679），最终请使用r5。

当前产物：jar/custom_spider.jar，5697366字节，MD5 `c4653a89e87f5a3498ec6198496541da`；BUILD `aurora-toolbar-20261107-r5`。四条目/关键DEX符号/MD5侧文件核验通过；本地D:/SI/影视jar/index.js两处MD5已同步，node --check通过。未发布线上/GitHub。DanmakuUIHelper SHA256保持73F493CC362E6302AFFFDC97607DA699C9AEEBDDC902408CA2B22ED8F8D0B4B6。
## r6 修订（18:59，历史交付）

- 搜索框关闭后的顶部残留：r5只隐藏control，T1顶部标题/分辨率在widget/top，时钟与常驻标题在display下，和control是独立层。r6打开搜索前调用已核对的T1/516 VideoActivity.o0(true)同步隐藏状态及原计时器，并收起明确映射的顶部提示。其他宿主及反射失败采用已映射容器隐藏。
- 搜索对话框失去/归还窗口焦点时绑定一次清理，选择弹幕和取消关闭都覆盖；归还焦点时立即清理并在下一次绘制前补清理，避免宿主同次焦点事件重显。不是每秒强制隐藏，不阻止用户后续正常唤栏。原DanmakuUIHelper.java没有修改。
- 音视频两按钮面板移除空格，宽度按实际列数收边，TV226dp/触屏211dp并受屏幕宽度限制；四列面板仍按420dp/390dp上限，48dp触摸高度不变。

验证：assembleRelease、git diff --check通过。TV Android7.1.2独立包模拟T1/T2/T3最终776项通过，18:57 PID22902，build/toolbar-smoke/r6/android7-tv-results.txt。新增真实AlertDialog确认/取消窗口焦点及下一帧清理测试，随后重新唤栏正常；音视频列数/实际宽度检查通过。HTML三端两条搜索关闭路径与音视频紧凑宽度通过，无脚本错误，见r6/html-results.json。临时测试包已卸载。真实宿主仍需手动升级r6核对，独立测试没有执行真实VideoActivity反射隐藏分支。

产物：jar/custom_spider.jar，5698085字节，MD5 `9a83534c4459c874b7665b8bf82dac77`；BUILD `aurora-toolbar-20261107-r6`。四条目与DEX标记/MD5侧文件核验通过；本地D:/SI/影视jar/index.js两处MD5同步，node --check通过。未发布线上/GitHub。原弹窗源文件SHA256保持73F493CC362E6302AFFFDC97607DA699C9AEEBDDC902408CA2B22ED8F8D0B4B6。

## r7 修订（平板布局兼容，历史交付）

- 平板192.168.31.54:44121实机为Android12、小米22081281AC（dagu）、影视5.5.6/556、arm64-v8a。前台播放“谷雨”第05集；已通过插件自身接口保存本机custom，仍显示旧栏且未出现新版绑定日志。
- 根因：T3的bottom及按钮行实际为androidx.appcompat.widget.LinearLayoutCompat，继承ViewGroup而非系统LinearLayout；旧映射的instanceof LinearLayout过滤使其回退legacy。T3归档采集已有该结构，此前独立测试用系统LinearLayout模拟，漏掉了这一差异。
- ToolbarHostMap改为识别系统LinearLayout和已知LinearLayoutCompat继承链，通过公开方法读写orientation/gravity；原宿主容器实例和节点匹配范围保持。Snapshot保存原LayoutParams对象及属性副本，回退恢复原对象，保留T3混淆参数子类sg1的类型。
- r6的搜索收屏、音视频两列收边及之前的时间轴/焦点修复继续包含；原DanmakuUIHelper.java不变。

验证：assembleRelease通过。独立检查包在Android7.1.2 TV盒子运行，T1/T2模拟原结构，T3由DexClassLoader加载归档T3/host.apk里的真实LinearLayoutCompat及其参数类；新增容器类型和原参数对象恢复断言。19:10:28最终828项通过（PID17475），日志build/toolbar-smoke/r7/android7-tv-results.txt。真实宿主APK仅作为独立测试包资产，不进入生产JAR。临时检查包已卸载，平板临时toolbar_diagnose.flag已移除，保留custom开关。

产物：jar/custom_spider.jar，5698472字节，MD5 `01a8a75b3eaddb76ecc7ad6a9cd6b5d5`；BUILD `aurora-toolbar-20261107-r7`。本地D:/SI/影视jar/index.js两处MD5已同步且node --check通过，未上传线上/GitHub。平板正在运行的JAR版本/MD5未取得，不能宣称已加载r7；需用户手动升级并彻底退出重进后检查T3绑定、触摸、全屏/旋转及面板开关。真实类独立测试通过不等于平板真实宿主升级验收通过。


## r8：手动头尾与自动章节分离、Auto 总开关和跳转暂停（历史，自动跳转暂停规则已被r9替代）

- 播放设置沿用宿主原片头/片尾按钮，紧接片尾加入“清除头尾”；仅清除当前宿主 History 的手动 opening/ending 及暂停备份，保留自动章节。不可用的宿主章节按钮不占网格；恢复可用时重新出现，焦点仍在面板内。
- 章节与标记中的头尾只允许自动来源：TMDB 匹配剧集，IntroDB/AniSkip 提供时间点；不再从原生手动 History 导入，也不将自动时间写回 History。原章节侧栏布局、搜索、扫码样式保留；移除旧的手动章节编辑后备界面入口。
- 旧 ChapterData 中 manualIntroOverride/manualOutroOverride 标记的侧独立迁移清理，未被手动覆盖的自动侧和已有自定义章节保留，后续自动查询补回供应商数据。automaticOnlyVersion=1，AUTO_MARKER_VERSION=3。
- Auto 关闭暂停所有头尾跳过，手动值持久暂存在 danmaku_native_skip_paused_v1，键为宿主 cid|History.key；原生有效值设零。开启后恢复，手动清除会同时清掉暂存值，避免复活。切换模式/首次运行同步当前订阅保存的 History，后续只检查当前播放记录。
- 所有超过9秒的前后跳转（包括Auto自身）暂停头尾10分钟；每次新跳转重新计时。严格大于9000ms；轮询扣除正常播放按速度推进的时间，避免正常倍速/延迟采样误判。显式JAR seek成功后立即暂停，宿主遥控/触摸跳转由播放采样识别。Auto片尾下一集仍有超过9秒未播时也进入暂停。
- 暂停使用内存中的单调时钟，只对当前播放会话有效；退出播放页（Activity停止/销毁）、新集或新播放源清零，重新进入同一集也不延续计时。元数据临时回退到mediasession不算重播。当前会话内关闭再打开Auto不绕过计时，10分钟到期且总开关开启才恢复；章节首行打开时显示暂停剩余时长。此规则替代原来的10秒跳变冷却及“本集永久禁跳”规则；新播放会话的初始10秒冷却保留。
- 原片头/片尾监听器不替换；仅添加文本观察器，在Auto关闭/暂停时及时停用新设置的值，退出新UI时移除。History读写和自动播放控制在主线程执行，自动动作执行前复查开关及暂停截止时间。

验证边界：电视192.168.31.41:5555上运行的是独立检查包。章节检查使用生产ChapterManager/FongMiNativeHelper和隔离History、网络桩，最终66项通过（21:54:46，PID23025）；覆盖关闭/恢复/清除、旧数据迁移、晚到回调、九秒边界、Auto自身跳转、重复延时、退出/重进/换集清零、到期恢复及快退到0。日志build/chapter-smoke/r8-session/android7-tv-results.txt。T1宿主APK只读核对History公开方法及VideoActivity原生跳过流程；没有修改APK。真实宿主升级后的播放验收仍待用户手动升级；不能将独立检查等同实机宿主通过。

本轮源文件DanmakuUIHelper.java有变更：只删除旧手动编辑后备逻辑并加入暂停文字，不能继续沿用r7“整个文件哈希不变”的表述；原搜索/二维码/章节侧栏外观代码保留，并与r7备份逐段比较确认。三端功能栏最终876项通过（21:52:27，PID15340），见build/toolbar-smoke/r8-final/android7-tv-results.txt；两种独立检查包均已卸载。未发布线上/GitHub，也未清除用户电视中的真实手动记录。

最终当前会话版产物：jar/custom_spider.jar，5695667字节，MD5 `67739ef36ac42782602d562be3fe310a`，BUILD `aurora-toolbar-20261107-r8`。四个JAR条目、MD5侧文件、当前会话重置及跳转关键DEX符号核验通过；DEX不含废弃的持久暂停偏好 danmaku_skip_pause_v1。本地D:/SI/影视jar/index.js两处MD5已同步，node --check通过；核验见build/chapter-smoke/r8-session/package-verification.json。

### 平板补充核对（端口37767）

设备Android12/影视5.5.6/556/arm64，宿主APK与T3归档MD5一致。只读核对History公开接口、当前History引用、原生片尾剩余时间语义及onStop继承回调；细节与待测边界见build/chapter-smoke/r8-session/tablet-check.md。首次独立包安装被系统拒绝；用户开启USB安装并确认升级后，旧无线ADB端口离线，等待新端口继续，未宣称平板运行检查通过。

## r9：仅手动跳转暂停、章节焦点、平板弹幕关闭（历史，暂停规则已由r10替代）

- 按用户最新纠正：Auto自动片头seek/片尾next不触发10分钟暂停。Auto seek登记本集目标及单调时刻，一次性消费匹配的进度结果；失败清除、换会话清除，不同目标仍检测手动跳转。宿主/media与MediaSession进度源切换不当作手动跳转；采样时钟改用elapsedRealtime。
- 用户手动跳转严格超过9秒，暂停10分钟；新手动跳转刷新期限，Auto总开关来回切换不绕过期限；退出重播清零，规则继续保留。
- TV真实复现：ScrollView路径刷新removeAllViews后焦点落到不可点击的rows容器，旧findFocus()!=null逻辑跳过补发。ChapterDialogFocus只接受有效可点击子行焦点，否则恢复首个可操作行；实际列表重建检查通过。RecyclerView重绑定同时同步背景/文字聚焦样式。
- 原章节侧栏外观保留；Auto状态改为手动暂停分:秒，每秒仅更新文字，不重建列表/抢焦点；关闭弹窗取消更新。无障碍描述附触发位置。
- 平板5.5.6的w50/y50取消选中调用Danmaku.empty，gf0.a把所有源取消选中，但xe2.e的orElse(list.get(0))回退第一条，仍setDanmakuSource。新增HostDanmakuGate，读取宿主默认prefs的danmaku_show及显式danmaku_load=false；T3/556按已核对的类型及字段读取实际源选择，全不选则停显，重选后按宿主开关恢复。原监听和选择框不变。每次自动推送入口、下载重试、发送前、延迟任务再次检查；关闭时取消自动重试，手动搜索选择仍保留。

验证：assembleRelease通过；TV独立检查最终84项通过（22:25:43，PID11818），见build/chapter-smoke/r9-final/android7-tv-results.txt，已卸载。功能栏上轮876项不重复计入本轮。r8平板真实已确认绑定T3（21:59:57），清除头尾、隐藏不可用宿主章节、时间轴固定、手动跳转暂停及刷新、开关不绕过暂停、退出重进后Auto开已核对。r9最终宿主仍需升级后复核，平板独立包被系统拒绝两次，不能写成平板独立检查通过。

TV最初暂停的触发日志已被chatty清理，无法恢复确切原因；r8包含Auto触发暂停以及静态倒计时显示，已在r9移除/修正。TV焦点复现XML位于build/chapter-smoke/r8-session/tv-toggled.xml及tv-toggle2.xml。平板原关闭路径静态核对及UI记录位于build/chapter-smoke/r9/。没有发布线上或GitHub。

最终r9产物：jar/custom_spider.jar，5697963字节，MD5 `4e57d984f022ebe15a9595dac7bc2aa5`。四条目、MD5侧文件、r9/焦点/弹幕关态/Auto凭据DEX符号通过；本地index.js两处MD5同步，node --check及git diff --check通过，见build/chapter-smoke/r9-final/package-verification.json。

## r10：本次播放片头、片尾各执行一次（当前）

- 按用户最新要求，删除九秒跳变阈值、10分钟暂停期限、Auto目标豁免凭据及暂停原因/倒计时。手动快进、快退、点章节不再暂停Auto，也不会清除已完成状态。
- SkipSession仅保存在内存，introDone/outroDone分别在对应控制成功时置位；某一侧执行不影响另一侧。播放进度离开后再退回、开关Auto、清除原生手动头尾、更新自动章节均不重置资格。失败不消耗次数，原3秒重试限制保留。
- 退出播放页再进入、换集、加载不同真实播放源创建新SkipSession，两侧重新计数；真实URL与MediaSession临时兜底切换不算新播放。后台成功回调只写原会话对象，主线程执行前再次检查会话，避免旧动作消耗新集资格。
- 保留新播放初始10秒保护；Auto总开关继续停用/恢复原生手动头尾备份。原生手动按钮、清除按钮、自动章节来源与r9弹幕关闭修复保留。
- 章节首行只显示Auto开/关，无暂停分秒；原布局和有效子行焦点恢复保留。版本标记aurora-toolbar-20261107-r10。

验证：assembleRelease成功；Android7.1.2 TV独立检查104项通过（22:45:23，PID31006），见build/chapter-smoke/r10/android7-tv-results.txt；独立包已卸载，未改真实影视设置。包含倒回不重跳、两侧独立、失败重试、手动seek无暂停、开关/清除/刷新不重置、会话更替与晚到动作隔离，以及章节焦点/弹幕关态回归。没有在平板运行本轮独立包，真实宿主需用户手动升级后验收。未上传线上或GitHub。

最终r10产物：jar/custom_spider.jar，5696868字节，MD5 `c20e63da9355437a7382122c33b8172f`。四条目与双ABI资产一致性通过；DEX包含SkipSession、两侧独立完成标记及r9焦点/弹幕关态修复，不含已删除的10分钟暂停符号。本地D:/SI/影视jar/index.js两处MD5已同步，node --check与git diff --check通过。核验见build/chapter-smoke/r10/package-verification.json。

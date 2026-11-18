# NFSNL 地图解包

《极品飞车：无极限》（NFS No Limits）赛道地图解包。只供本地研究，解出来的是 EA 的资源，请勿分发。

## 目录

| 路径 | 内容 |
|---|---|
| `viewer/index.html` | **赛道重建查看器**，双击打开即可（需要联网加载 three.js） |
| `viewer/data/` | 查看器数据：每张地图一个 `.js`，`index.js` 是地图列表 |
| `viewer/scene/` | 查看器"原赛道"模式用的场景：游戏静态几何 + 贴图 + 烘焙光照图集，含 6 个限定版（约 350MB，按需加载） |
| `glb/` | 每张地图的静态场景模型（带贴图的 GLB，可双击打开或拖进 Blender） |
| `packs/` | 从模拟器拉下来的原始地图包（约 1.2GB） |
| `tools/` | 解包脚本 |
| `minimap/` | **NFSNL Minimap** 悬浮小地图 APK 及源码（需要 root）（`build.sh` 用 SDK 命令行工具直接打包，不需要 Gradle） |
| `memscan/` | root 内存扫描工具（定位镜头/车辆坐标用） |

## 脚本

```bash
python3 tools/pull_packs.py              # 从模拟器（adb 127.0.0.1:5555）重新拉取地图包到 packs/
python3 tools/export_map.py              # packs/ → glb/（静态场景几何 + 贴图）
python3 tools/export_tracks.py           # packs/ → viewer/data/（赛道样条线、路宽、比赛检查点；原版地图 + 限定版目录条目）
python3 tools/export_web_scene.py        # packs/ → viewer/scene/（原赛道渲染：场景 + 光照贴图，base64 GLB；含限定版，--no-events 跳过）
python3 tools/export_fx.py               # packs/ → viewer/fx/（限定地图的粒子特效：蝙蝠、火光、闪电、彩纸等）
```

## 查看器功能

- **地图：** 11 张原版地图（Region 05 游戏里没有赛道），下面单独一组"限定地图"：圣诞 2022、万圣节 2024、冬季 2024（Hopebridge），万圣节 2023（Mountain，血月），万圣节 2022（Desert），圣诞 2023（Rural）。限定版的比赛路线和原版相同，选中后自动切到"原赛道"渲染。
- **渲染：** "示意"是按海拔、坡度或弯道着色的路面；"原赛道"加载游戏静态场景，用漫反射贴图 × 烘焙光照（Beast lightmap，已应用 scale/bias）+ 发光贴图还原游戏里的样子。
- **比赛镜头：** 点工具栏的 ▶、比赛卡片上的 ▶ 或"▶ 全程"，沿路面中线匀速跑完。可选追尾、车内、航拍三种视角，车速 120/200/300/400 km/h；HUD 上的小车按钮（或按 H）可以隐藏小橙车，浏览器会记住这个设置。空格暂停，←/→ 拖动进度，Esc 退出；点剖面图也能跳到对应位置。终点有一道横跨比赛车道的红色光幕，冲线时会闪亮。
- **标记：** 起点为白色图钉，终点为黑色图钉，比赛结束后的停车点为灰色小图钉。

`export_map.py region04` 这类写法可以只导出名字里包含该字段的地图；`--tex-max 2048` 导出更高分辨率的贴图。

`tools/zstandard.py` 是兼容层：Python 3.14 没装 `zstandard` 库时，用标准库的 `compression.zstd` 代替，供上面的脚本解压游戏包。

## 限定地图

限定活动的场景配置在 `tools/events.py`。每个活动在游戏里由三部分组成：

- `<活动>.variant.scene_static.sba`：整张地图按活动重新烘焙光照的版本（几何体和原版相同，比如万圣节改成夜晚、血月）。
- `<活动>.layer.scene_static.sba`：活动新增的道具，比如圣诞树、彩灯、南瓜、鬼面灯牌，带自己的光照贴图。这两种文件在标签为 `prefabs` 的包里。
- `remap_LTS_*` 包：和原版同名的替换贴图，比如积雪地面、挂霜的树、极光天空盒，活动期间覆盖原版贴图。

导出时，限定版优先用自己活动包里的贴图；原版地图只用原版包里的贴图，不会混进活动贴图。原版的 `scene.sb` 里本来就带着各活动的图层（`layer_christmas_2022` 等），游戏按活动开关显示。

**特效：** 活动图层里挂着 `NFSModel` 特效模型，模型旁的 `.bin` 写着要挂的粒子特效名（`vfx_attach:<名字>:<节点>`），粒子定义在 `vfx` 标签包的 `.sbfx`（SBIN：发射器、受力、寿命曲线、四边形渲染器、贴图）。`tools/export_fx.py` 把它们导出到 `viewer/fx/`，查看器在原赛道模式下按游戏参数重新模拟（镜头 700 米内），图层面板可以关掉。`.m3g` 模型本身是未知的压缩格式，读不出来，所以特效放在模型原点、模型本身（UFO、火车等）不显示，跟着动画移动的特效（火车烟）也跳过了。

更早的活动（Devil's Night、Drift Macabre、Shadowfall、Lunar、圣诞机场、2021 万圣节乡村等）用的是另一种 `.m3g` 叠加模型，目前没有导出。

## 数据来源

- `*.scene_static.sba`：静态几何（实例、变换、量化顶点和索引缓冲）
- `*.lit_scene_*.lightmaps.sba`：`BeastMaterialVariables`，即每个实例的漫反射和发光贴图
- `*.scene.sb`：场景树
  - `track_info/SplineLayer/<路线>/{track_spline, race_spline, traffic_spline}`：中心线及其路宽（`TrackRib`）、4 条比赛车道、交通车道
  - `TrackLayers/<路线>/Races/raceNN_forward|reverse/{start_race, finish_race, end_race}`：每场比赛的检查点

以上文件都是不加密的自描述 SBIN，由 `tools/sbtyped.py` 通用解析。坐标系 Y 轴朝上，单位为米。

## 悬浮小地图（minimap/）

- **只支持 root。** 读取游戏内存中的比赛镜头矩阵，路径为 `libapp.so + 0x378C158 → 对象 + 0x30`（4×4 行主序矩阵，最后一行是坐标；指针高字节带 `b4` 标签，需要屏蔽掉）。根据坐标自动识别地图；进入比赛、镜头在起跑线后停稳时，按镜头位置（某场比赛起点后方几十米内、贴着赛道）和朝向（视线方向是矩阵第三行取反）立刻识别比赛；中途打开时按位置和行驶方向推测。只读，不修改游戏。
- 地图、比赛、自己的车和对手都自动识别，App 里没有手动选择；没有 root 时只会提示开启 root（3.0 起去掉了录屏识别 HUD 的模式）。
- **车辆：** 车辆对象没有简短的静态指针路径，但游戏在各场比赛之间会复用同一批车辆对象。读取进程会自己学习：载入比赛、镜头在发车位停稳时，扫描附近的 4×4 矩阵；发车后，始终在镜头前方约 10 米的就是玩家车，另一辆一起移动的就是对手（单挑）。学到的地址保存在 `/data/local/tmp/nfsnl_minimap_cars.txt`，同一个游戏进程里一直有效。从第二场起，载入时就能按车的位置和车头朝向识别比赛，对手在小地图上显示为红色箭头。
- 每次运行的日志写在 `Android/data/com.nfsnl.minimap/files/runs/`。
- 偏移量对应当前游戏版本。游戏更新后需要用 `memscan/` 重新定位。

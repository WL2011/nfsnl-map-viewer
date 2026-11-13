# NFSNL 地图解包

《极品飞车：无极限》（NFS No Limits）赛道地图解包。只供本地研究，解出来的是 EA 的资源，请勿分发。

## 目录

| 路径 | 内容 |
|---|---|
| `viewer/index.html` | **赛道重建查看器**，双击打开即可（需要联网加载 three.js） |
| `viewer/data/` | 查看器数据：每张地图一个 `.js`，`index.js` 是地图列表 |
| `viewer/scene/` | 查看器"原赛道"模式用的场景：游戏静态几何 + 贴图 + 烘焙光照图集（约 200MB，按需加载） |
| `glb/` | 每张地图的静态场景模型（带贴图的 GLB，可双击打开或拖进 Blender） |
| `packs/` | 从模拟器拉下来的原始地图包（约 1GB） |
| `tools/` | 解包脚本 |

## 脚本

```bash
python3 tools/pull_packs.py              # 从模拟器（adb 127.0.0.1:5555）重新拉取地图包到 packs/
python3 tools/export_map.py              # packs/ → glb/（静态场景几何 + 贴图）
python3 tools/export_tracks.py           # packs/ → viewer/data/（赛道样条线、路宽、比赛检查点；默认只导原版地图，加 --with-events 导出活动版本）
python3 tools/export_web_scene.py        # packs/ → viewer/scene/（原赛道渲染：场景 + 光照贴图，base64 GLB）
```

## 查看器功能

- **地图：** 列表只收录 11 张原版地图（Region 05 游戏里没有赛道）。
- **渲染：** "示意"是按海拔、坡度或弯道着色的路面；"原赛道"加载游戏静态场景，用漫反射贴图 × 烘焙光照（Beast lightmap，已应用 scale/bias）+ 发光贴图还原游戏里的样子。
- **比赛镜头：** 点工具栏的 ▶、比赛卡片上的 ▶ 或"▶ 全程"，沿路面中线匀速跑完。可选追尾、车内、航拍三种视角，车速 120/200/300 km/h。空格暂停，←/→ 拖动进度，Esc 退出；点剖面图也能跳到对应位置。终点有一道横跨比赛车道的红色光幕，冲线时会闪亮。
- **标记：** 起点为白色图钉，终点为黑色图钉，比赛结束后的停车点为灰色小图钉。

`export_map.py region04` 这类写法可以只导出名字里包含该字段的地图；`--tex-max 2048` 导出更高分辨率的贴图。

## 数据来源

- `*.scene_static.sba`：静态几何（实例、变换、量化顶点和索引缓冲）
- `*.lit_scene_*.lightmaps.sba`：`BeastMaterialVariables`，即每个实例的漫反射和发光贴图
- `*.scene.sb`：场景树
  - `track_info/SplineLayer/<路线>/{track_spline, race_spline, traffic_spline}`：中心线及其路宽（`TrackRib`）、4 条比赛车道、交通车道
  - `TrackLayers/<路线>/Races/raceNN_forward|reverse/{start_race, finish_race, end_race}`：每场比赛的检查点

以上文件都是不加密的自描述 SBIN，由 `tools/sbtyped.py` 通用解析。坐标系 Y 轴朝上，单位为米。

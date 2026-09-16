当触发qte时，首先主标题会显示点击左键开始，副标题是一段框，需要左键点击，使指针移动，让指针保持在绿色区域内，指针上下略凸出整体框。

---

## 一、 核心开发细节

### 1. 抓取与分析 CustomFishing GUI (Screen)
CustomFishing 的 QTE 通常在客户端表现为一个自定义的 GUI/HUD 界面（如包含绿区条和移动指针的 Screen）。
* **定位数据域**：在 1.21.1 中，建议使用 **Mixin** 注入目标 Screen/Gui 渲染或 Tick 方法，提取指针当前位置与绿色区域范围。
* **统一相对比例**：游戏界面可能会受到玩家 GUI 缩放倍率（GUI Scale）的影响，绝对像素点容易出问题。计算时请统一转换为 `0.0 ~ 1.0` 的**相对比例**：

$$\text{相对位置} = \frac{P_{x} - X_{start}}{\text{BarWidth}}$$



```java
KeyMapping.click(client.options.keyAttack.getKey());
```

### 2. 控制算法：惯性预测与迟滞比较（Hysteresis）
单纯的“小于最小值就按，大于最大值就松”会导致指针在临界点高频震荡，极易失败或被检测。
* **速度预测（Velocity Prediction）**：记录上一帧指针位置 $P_{prev}$，计算出速度 $V = P_{curr} - P_{prev}$。基于预测位置判断：

$$P_{predict} = P_{curr} + V \times \text{预判系数}$$

* **安全边距（Safety Margin）**：对目标区间进行收缩。例如绿色区域的真实比例区间是 `[0.20, 0.40]`，自动控制的实际目标区间应设为 `[0.23, 0.37]`，防止惯性冲出绿区。

---

## 二、 关键注意事项

### 1. 映射表（Mojang Mappings）兼容性
1.21.1 版本开发须统一采用 Mojang 官方映射表（Official Mojang Mappings）。配置 `build.gradle` 时务必确保映射表正确，避免因字段混淆（如 `options.keyAttack`）导致运行时崩溃。

### 2. 线程安全性与执行时机
* **必须在客户端主线程操作**：切勿在自定义异步线程（如 `CompletableFuture` 或多线程）中直接修改 `keyAttack.setDown()`，否则在 1.21.1 中极易触发 `ConcurrentModificationException` 或游戏渲染假死。
* **推荐 Hook 节点**：
  * **Fabric**：注册 `ClientTickEvents.END_CLIENT_TICK`
  * **NeoForge / Forge**：监听 `ClientTickEvent.Post`

### 3. 服务端反作弊与拟人化（针对 CustomFishing 联机环境）
CustomFishing 经常部署在含有反作弊插件（如 GrimAC, Vulcan, Matrix）的服务器中：
* **按压时间微随机化**：避免固定 Tick 间隔的绝对离散按压，在按压/释放逻辑中加入 $15 \sim 40\text{ms}$ 的随机延迟抖动。
* **位置漂移**：允许指针在绿区内部合理漂移，不要试图将指针一直死死钳制在绿色区域正中心。

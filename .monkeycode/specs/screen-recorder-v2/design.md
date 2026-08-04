# 屏幕录制 V2 技术设计

## 架构概览

### 当前架构 (V1)
```
MainActivity → ScreenRecorderService (单体，自包含编码管道)
                   ├── 手动 MediaCodec + VirtualDisplay + MediaMuxer
                   ├── FloatingView (UI 线程)
                   └── 无音频
```

### 目标架构 (V2)
```
MainActivity → ScreenRecorderService (协调器)
                   ├── RecorderEngine (核心录制引擎) ← 整合已有代码
                   │     ├── 视频: MediaCodec + VirtualDisplay + MediaMuxer
                   │     ├── 音频: 通过 AudioCaptureManager (可选)
                   │     ├── 暂停/恢复控制
                   │     └── 多轨 muxer 协调
                   ├── AudioCaptureManager (音频) ← 整合已有代码
                   │     ├── 内部音频 (AudioPlaybackCapture, API 29+)
                   │     ├── 麦克风 (AudioSource.MIC)
                   │     └── AAC 编码
                   └── FloatingView (UI 线程)
```

## 模块设计

### 1. ScreenRecorderService 重构

当前 Service 包含约 300 行重复的编码管道代码。重构后应：

- **职责**：服务生命周期管理、通知、权限传递、组件协调
- **不包含**：编码器配置、VirtualDisplay 创建、编码循环

#### 1.1 核心方法
- `onStartCommand(ACTION_PREPARE)`：启动前台通知，等待授权
- `onStartCommand(ACTION_START)`：获取 MediaProjection → 创建 RecorderEngine → 传递给引擎启动
- `onStartCommand(ACTION_STOP)`：停止引擎 → 清理资源
- `onStartCommand(ACTION_PAUSE)`：暂停引擎
- `onStartCommand(ACTION_RESUME)`：恢复引擎

#### 1.2 新增 Action
```
ACTION_PAUSE  = "com.monkeycode.screenrecorder.PAUSE"
ACTION_RESUME = "com.monkeycode.screenrecorder.RESUME"
```

### 2. RecorderEngine 整合

`RecorderEngine.kt` 已有完整的录制核心逻辑。整合时需要以下改动：

#### 2.1 现有功能保留
- `createEncoder()`：编码器选择和 MediaFormat 配置（含高级模式差异）
- `addTrackAndMaybeStart()`：多轨 muxer 协调
- `encodeLoop()`：带暂停支持的编码循环
- `start()` / `stop()` / `pause()` / `resume()`：生命周期管理
- `writeSampleData()`：外部写入采样数据（音频用）

#### 2.2 需要的改动
1. **构造参数增强**：增加 `audioSource: AudioSourceType` 参数，决定是否启动音频采集
2. **`start()` 回调**：已有的 `onStartCallback` 用于 `startMuxer()` 后的通知，保留
3. **暂停传播**：`pause()` 和 `resume()` 需要传播给 `AudioCaptureManager`
4. **错误报告**：增加 `onError: (String) -> Unit` 回调，向 Service 传递错误
5. **停止完成回调**：增加 `onStopped: (outputPath: String) -> Unit`，通知 Service 文件已就绪

#### 2.3 接口定义
```kotlin
class RecorderEngine(
    private val context: Context,
    private val config: RecorderConfig,
    private val projection: MediaProjection,
    private val outputPath: String,
    private val onStartCallback: (() -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
    private val onStopped: ((String) -> Unit)? = null
)
```

### 3. AudioCaptureManager 整合

`AudioCaptureManager.kt` 已有完整实现。整合时需要以下改动：

#### 3.1 现有功能保留
- 四种音频源采集逻辑
- AAC 编码管道
- 内部音频 `AudioPlaybackCapture` 配置
- 麦克风 `AudioRecord` 采集

#### 3.2 需要的改动
1. **构造参数改为 `RecorderConfig`**：接收完整配置而非单独的 `AudioSourceType`
2. **暂停支持**：增加 `pause()` / `resume()` 方法控制采集线程
3. **停止完成回调**：增加 `onStopped: () -> Unit`
4. **错误处理**：采集失败时通过回调通知，允许 Service 降级为静音模式

#### 3.3 与 RecorderEngine 的交互
```
AudioCaptureManager.start() → 启动音频采集线程
    └── 编码完成后 → engine.writeSampleData(audioTrackIndex, buffer, bufferInfo)

AudioCaptureManager.stop() → 停止采集并发送 EOS 给音频编码器
```

### 4. 数据流

```
  [屏幕] → VirtualDisplay → MediaCodec(Video) → ┐
                                                   ├→ MediaMuxer → .mp4
  [内部音频] → AudioRecord → MediaCodec(AAC) → ────┤
  [麦克风]   → AudioRecord → MediaCodec(AAC) → ────┘
```

### 5. 状态机

```
IDLE → PREPARING → STARTING → RECORDING → STOPPED → IDLE
                     ↓           ↑    ↑
                   FAILED    PAUSED → RESUME
```

### 6. 线程模型

| 线程 | 用途 |
|------|------|
| Main Thread | UI、通知、FloatingView、Toast |
| HandlerThread (RecorderThread) | Service 内部协调、引擎生命周期 |
| Thread (VideoDrain) | 视频编码输出 drain (RecorderEngine) |
| Thread (AudioCapture) | 音频采集 + AAC 编码 (AudioCaptureManager) |
| Thread (AudioDrain) | 音频编码输出 drain (AudioCaptureManager) |

### 7. 文件改动清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `ScreenRecorderService.kt` | 重写 | 移除重复编码逻辑，改为协调器 |
| `RecorderEngine.kt` | 修改 | 增加音频集成、暂停传播、错误回调 |
| `AudioCaptureManager.kt` | 修改 | 改为接收 RecorderConfig、增加暂停支持 |
| `FloatingView.kt` | 修改 | 暂停按钮传播到 Service |
| `ScreenRecorderApp.kt` | 微调 | 无需改动（已有状态管理） |
| `PreferencesManager.kt` | 不改动 | 配置模型不变 |
| `MainActivity.kt` | 微调 | 暂停/恢复由 Service/悬浮窗处理，Activity 不参与 |
| `floating_view.xml` | 不改动 | 已有时长和暂停按钮 |

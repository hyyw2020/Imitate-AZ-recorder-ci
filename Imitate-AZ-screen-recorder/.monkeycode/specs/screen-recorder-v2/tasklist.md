# 需求实施计划

- [ ] 1. 重构 RecorderEngine 接口与回调系统
   - 修改构造参数：增加 `onError` 和 `onStopped` 回调 (R4.1)
   - 修改 `start()` 方法：在 muxer 启动失败时调用 `onError` 回调 (R5.1)
   - 修改 `stop()` 方法：完成文件写入后调用 `onStopped(outputPath)` 回调 (R2.2.1)
   - 修改 `pause()`/`resume()` 方法：增加状态判断，已在目标状态时忽略 (R2.1.1, R2.1.2)
   - 移除 `RecorderEngine` 中已废弃的 `isPaused` 标记的多余日志

- [ ] 2. 整合 AudioCaptureManager 到 RecorderEngine
   - 修改 `AudioCaptureManager` 构造参数：接收 `RecorderConfig` 替代 `AudioSourceType` (R1.1.1-R1.1.4)
   - 在 `AudioCaptureManager` 中增加 `pause()`/`resume()` 方法，控制采集线程的暂停标志 (R2.1.1, R2.1.2)
   - 在 `RecorderEngine.start()` 中根据 `config.audioSource` 条件创建并启动 `AudioCaptureManager` (R4.2)
   - 在 `RecorderEngine` 中增加 `audioCaptureManager` 引用，`stop()`/`pause()`/`resume()` 传播到音频管理层 (R4.4)
   - 处理音频采集失败：通过 `onError` 回调通知，允许 Service 降级为静音模式 (R5.3)

- [ ] 3. 检查点 - 确保 RecorderEngine 与 AudioCaptureManager 集成正确
  - 验证编解码器配置可通过，且编译无错误
  - 确保所有回调链完整：onStartCallback → onError → onStopped

- [ ] 4. 重写 ScreenRecorderService 为协调器模式
  - 移除 Service 中所有手动编码管道代码（`drainVideo`、`pickEncoder`、直接 `MediaCodec`/`VirtualDisplay`/`MediaMuxer` 调用）(R4.1)
  - 创建新的 `startRecording()` 方法：获取 MediaProjection → 创建 RecorderEngine（含 AudioCaptureManager）→ 调用 engine.start() (R4.2, R4.3)
  - 连接回调：`engine.onStartCallback` → 更新通知为录制状态 + 显示悬浮窗 (R4.1)
  - 连接回调：`engine.onError` → `showError()` + doStopRecording (R5.1)
  - 连接回调：`engine.onStopped` → 通知用户文件路径 + 清理资源 (R2.2.1, R5.2)
  - 实现 `ACTION_PAUSE` / `ACTION_RESUME` 处理，调用 `engine.pause()` / `engine.resume()` (R2.1.1, R2.1.2)

- [ ] 5. 实现 MediaProjection.onStop 自动停止
  - 在 Service 的 `registerCallback` 中（已存在），确保 `onStop` 回调调用 `engine.stop()` 而非直接调用 `doStopRecording()` (R2.2.2)

- [ ] 6. 连接 FloatingView 暂停/恢复到 Service
  - 修改 `FloatingView` 的 `onPauseToggle` 回调：新增 `onPause: () -> Unit` 和 `onResume: () -> Unit` 两个独立回调替代布尔值
  - 在 Service 中连接悬浮窗暂停/恢复回调到 `ACTION_PAUSE` / `ACTION_RESUME` 处理 (R4.4)
  - 在 `RecorderEngine.pause()` 时通过回调更新悬浮窗 `updatePauseState(true)` (R4.4)

- [ ] 7. 处理音频权限降级
  - 在 Service 的 ACTION_START 流程中检查 `RECORD_AUDIO` 权限 (R1.1.1-R1.1.4)
  - 无麦克风权限但仍需内部音频时，从 Activity 传递权限状态到 Service
  - AudioCaptureManager 启动失败时自动降级：内部音频失败 → 静音，麦克风失败 → 静音 (R5.3)

- [ ] 8. 高级录制模式生效
  - 在 `RecorderEngine.createEncoder()` 中确认 `config.recordMode == ADVANCED` 的分支逻辑完整 (R3.1, R3.2)
  - 删除 `ScreenRecorderService` 中遗留的硬编码编码器参数（I 帧间隔=2、无 VBR），统一由 RecorderEngine 管理 (R4.1)

- [ ] 9. 检查点 - 完整录制流程验证
  - 构建 Release APK 并确认无编译错误
  - 确保 `drainVideo` 等旧方法已完全移除，无死代码残留

- [ ]* 10. 编写回归测试
  - 为 RecorderEngine 的状态转换（start → pause → resume → stop）编写单元测试
  - 为 AudioCaptureManager 的音频源选择和降级逻辑编写单元测试
  - 验证 `RecorderConfig` 中 `outputUri` 的 `@Transient` 注释仍有效

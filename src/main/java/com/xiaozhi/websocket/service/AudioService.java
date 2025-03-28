package com.xiaozhi.websocket.service;

import com.xiaozhi.websocket.audio.detector.VadDetector;
import com.xiaozhi.websocket.audio.processor.OpusProcessor;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

@Service
public class AudioService {

    private static final Logger logger = LoggerFactory.getLogger(AudioService.class);

    // 播放帧持续时间（毫秒）
    private static final int FRAME_DURATION_MS = 60;

    // 默认音频采样率和通道数
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int DEFAULT_CHANNELS = 1;
    // 创建线程池用于异步发送音频和预处理
    private final ExecutorService audioSenderExecutor = Executors.newCachedThreadPool();
    private final ExecutorService audioPreprocessExecutor = Executors.newCachedThreadPool();

    // 为每个会话维护一个音频发送队列
    private final Map<String, Queue<ProcessedAudioTask>> audioQueues = new ConcurrentHashMap<>();

    // 跟踪每个会话的发送状态
    private final Map<String, AtomicBoolean> isProcessingMap = new ConcurrentHashMap<>();

    @Resource
    private MessageService messageService;

    @Autowired
    private OpusProcessor opusProcessor;

    @Autowired
    private VadDetector vadDetector;

    /**
     * 已处理的音频任务类，包含已转码的音频数据
     */
    private static class ProcessedAudioTask {
        private final List<byte[]> opusFrames;
        private final String text;
        private final boolean isFirstText;
        private final boolean isLastText;
        private final String originalFilePath; // 保留原始文件路径，用于后续删除

        public ProcessedAudioTask(List<byte[]> opusFrames, String text, boolean isFirstText, boolean isLastText,
                String originalFilePath) {
            this.opusFrames = opusFrames;
            this.text = text;
            this.isFirstText = isFirstText;
            this.isLastText = isLastText;
            this.originalFilePath = originalFilePath;
        }

        public List<byte[]> getOpusFrames() {
            return opusFrames;
        }

        public String getText() {
            return text;
        }

        public boolean isFirstText() {
            return isFirstText;
        }

        public boolean isLastText() {
            return isLastText;
        }

        public String getOriginalFilePath() {
            return originalFilePath;
        }
    }

    /**
     * 音频处理结果类
     */
    public static class AudioProcessResult {
        private List<byte[]> opusFrames;
        private long durationMs;

        public AudioProcessResult(List<byte[]> opusFrames, long durationMs) {
            this.opusFrames = opusFrames;
            this.durationMs = durationMs;
        }

        public List<byte[]> getOpusFrames() {
            return opusFrames;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }

    /**
     * 初始化会话的音频处理
     */
    public void initializeSession(String sessionId) {
        vadDetector.initializeSession(sessionId);
        // 初始化音频队列和处理状态
        audioQueues.putIfAbsent(sessionId, new LinkedBlockingQueue<>());
        isProcessingMap.putIfAbsent(sessionId, new AtomicBoolean(false));
    }

    /**
     * 处理接收到的音频数据
     * 
     * @param sessionId 会话ID，用于区分不同设备
     * @param opusData  Opus编码的音频数据
     * @return 如果语音结束，返回完整的音频数据；否则返回null
     */
    public byte[] processIncomingAudio(String sessionId, byte[] opusData) {
        try {
            // 1. 解码Opus数据为PCM
            byte[] pcmData = opusProcessor.decodeOpusFrameToPcm(opusData);

            // 2. 使用VAD处理PCM数据
            byte[] completeAudio = vadDetector.processAudio(sessionId, pcmData);

            // 3. 如果检测到语音结束，返回完整的音频数据
            return completeAudio;
        } catch (Exception e) {
            logger.error("处理音频数据时发生错误 - SessionId: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 清理会话的音频处理状态
     */
    public void cleanupSession(String sessionId) {
        vadDetector.resetSession(sessionId);
        // 清理音频队列
        Queue<ProcessedAudioTask> queue = audioQueues.get(sessionId);
        if (queue != null) {
            queue.clear();
        }
        // 重置处理状态
        AtomicBoolean isProcessing = isProcessingMap.get(sessionId);
        if (isProcessing != null) {
            isProcessing.set(false);
        }
    }

    /**
     * 处理音频文件，提取PCM数据并转换为Opus格式
     * 
     * @param audioFilePath 音频文件路径
     * @param sampleRate    采样率
     * @param channels      通道数
     * @return 处理结果，包含opus数据和持续时间
     */
    public AudioProcessResult processAudioFile(String audioFilePath, int sampleRate, int channels) throws Exception {
        // 从MP3获取PCM数据
        byte[] pcmData = extractPcmFromAudio(audioFilePath);
        long durationMs = calculateDuration(pcmData, sampleRate, channels);

        // 转换为Opus格式
        List<byte[]> opusFrames = opusProcessor.convertPcmToOpus(pcmData, sampleRate, channels, FRAME_DURATION_MS);

        return new AudioProcessResult(opusFrames, durationMs);
    }

    /**
     * 从音频文件提取PCM数据
     */
    private byte[] extractPcmFromAudio(String audioFilePath) throws Exception {
        // 创建临时PCM文件
        File tempPcmFile = File.createTempFile("temp_pcm_extract_", ".pcm");
        String tempPcmPath = tempPcmFile.getAbsolutePath();
        try {
            // 使用FFmpeg直接将MP3转换为PCM
            String[] command = {
                    "ffmpeg",
                    "-i", audioFilePath,
                    "-f", "s16le", // 16位有符号小端格式
                    "-acodec", "pcm_s16le",
                    "-y",
                    tempPcmPath
            };

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // 等待进程完成
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("FFmpeg提取PCM数据失败，退出码: {}", exitCode);
                throw new RuntimeException("PCM数据提取失败");
            }

            // 读取PCM文件内容
            File pcmFile = new File(tempPcmPath);
            byte[] pcmData = new byte[(int) pcmFile.length()];
            try (FileInputStream fis = new FileInputStream(pcmFile)) {
                fis.read(pcmData);
            }

            return pcmData;
        } finally {
            // 删除临时文件
            tempPcmFile.delete();
        }
    }

    /**
     * 计算音频持续时间（毫秒）
     */
    private long calculateDuration(byte[] pcmData, int sampleRate, int channels) {
        // PCM 16位数据，每个样本2字节
        int bytesPerSample = 2;
        int totalSamples = pcmData.length / (bytesPerSample * channels);
        return (long) (totalSamples * 1000.0 / sampleRate);
    }

    /**
     * 简化版本的发送音频方法
     */
    public void sendAudio(WebSocketSession session, String audioFilePath, String text) throws Exception {
        sendAudio(session, audioFilePath, text, true, true);
    }

    /**
     * 将音频添加到发送队列，并开始处理队列（如果尚未开始）
     * 此方法会先异步预处理音频，然后再添加到队列
     * 
     * @param session       WebSocket会话
     * @param audioFilePath 音频文件路径
     * @param text          文本内容
     * @param isFirstText   是否是第一段文本
     * @param isLastText    是否是最后一段文本
     */
    public void sendAudio(WebSocketSession session, String audioFilePath, String text, boolean isFirstText,
            boolean isLastText) throws Exception {

        if (session == null || !session.isOpen()) {
            return;
        }

        String sessionId = session.getId();

        // 确保会话已初始化
        initializeSession(sessionId);

        // 异步预处理音频
        audioPreprocessExecutor.submit(() -> {
            try {
                // 预处理音频文件
                AudioProcessResult result = processAudioFile(audioFilePath, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS);

                // 创建已处理的音频任务
                ProcessedAudioTask task = new ProcessedAudioTask(
                        result.getOpusFrames(),
                        text,
                        isFirstText,
                        isLastText,
                        audioFilePath);

                // 添加到发送队列
                audioQueues.get(sessionId).add(task);
                // 发送开始信号和句子开始信号
                if (isFirstText) {
                    sendStart(session);
                }
                // 如果当前没有处理任务，开始处理
                if (isProcessingMap.get(sessionId).compareAndSet(false, true)) {
                    processNextAudio(session);
                }
            } catch (Exception e) {
                logger.error("音频预处理失败: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 处理队列中的下一个音频任务
     */
    private void processNextAudio(WebSocketSession session) {
        String sessionId = session.getId();
        Queue<ProcessedAudioTask> queue = audioQueues.get(sessionId);

        if (queue == null) {
            logger.error("音频队列未初始化 - SessionId: {}", sessionId);
            return;
        }

        // 检查队列是否为空
        ProcessedAudioTask task = queue.poll();

        if (task == null) {
            // 队列为空，设置处理状态为false
            isProcessingMap.get(sessionId).set(false);
            return;
        }

        // 异步处理音频任务
        audioSenderExecutor.submit(() -> {
            try {

                sendSentenceStart(session, task.getText());

                // 获取已处理的Opus帧
                List<byte[]> opusFrames = task.getOpusFrames();

                // 开始发送音频帧
                long startTime = System.nanoTime();
                long playPosition = 0;

                for (int i = 0; i < opusFrames.size(); i++) {

                    byte[] frame = opusFrames.get(i);

                    // 计算预期发送时间（纳秒）
                    long expectedTime = startTime + (playPosition * 1_000_000);
                    long currentTime = System.nanoTime();
                    long delayNanos = expectedTime - currentTime;

                    // 执行延迟
                    if (delayNanos > 0) {
                        // 转换为毫秒和纳秒余数
                        long delayMillis = delayNanos / 1_000_000;
                        int delayNanosRemainder = (int) (delayNanos % 1_000_000);

                        if (delayMillis > 0 || delayNanosRemainder > 0) {
                            Thread.sleep(delayMillis, delayNanosRemainder);
                        }
                    }

                    // 再次检查会话是否仍然打开
                    if (session.isOpen()) {
                        // 直接发送Opus帧
                        session.sendMessage(new BinaryMessage(frame));

                        // 更新播放位置（毫秒）
                        playPosition += FRAME_DURATION_MS;
                    } else {
                        logger.info("会话已关闭，停止发送音频");
                        cleanupSession(sessionId);
                        return;
                    }
                }

                // 删除音频文件
                deleteAudioFiles(task.getOriginalFilePath());

                // 发送句子结束消息
                if (session.isOpen()) {
                    if (task.isLastText()) {
                        // 使用内部方法发送停止指令，避免触发清空队列
                        sendStop(session);
                    }
                }

                // 处理下一个音频任务
                processNextAudio(session);

            } catch (Exception e) {
                logger.error("发送音频数据时发生错误: {}", e.getMessage(), e);
                // 出错时也尝试处理下一个任务
                processNextAudio(session);
            }
        });
    }

    /**
     * 删除音频文件及其相关文件（如同名的VTT文件）
     * 
     * @param audioPath 音频文件路径
     * @return 是否成功删除
     */
    public boolean deleteAudioFiles(String audioPath) {
        boolean success = true;
        try {
            // 删除原始音频文件
            File audioFile = new File(audioPath);
            if (audioFile.exists()) {
                if (!audioFile.delete()) {
                    logger.warn("无法删除音频文件: {}", audioPath);
                    success = false;
                }
            }

            // 删除可能存在的VTT文件
            File vttFile = new File(audioPath + ".vtt");
            if (vttFile.exists()) {
                if (!vttFile.delete()) {
                    logger.warn("无法删除VTT文件: {}", vttFile.getPath());
                    success = false;
                }
            }
            return success;
        } catch (Exception e) {
            logger.error("删除音频文件时发生错误: {}", audioPath, e);
            return false;
        }
    }

    // 发送TTS句子开始指令（包含文本）
    public void sendSentenceStart(WebSocketSession session, String text) {
        messageService.sendMessage(session, "tts", "sentence_start", text);
    }

    // 发送TTS句子结束指令
    public void sendSentenceEnd(WebSocketSession session, String text) {
        messageService.sendMessage(session, "tts", "sentence_end", text);
    }

    // 发送TTS开始指令
    public void sendStart(WebSocketSession session) {
        messageService.sendMessage(session, "tts", "start");
    }

    /**
     * 发送TTS停止指令，同时清空音频队列并中断当前发送
     */
    public void sendStop(WebSocketSession session) {
        if (session == null) {
            logger.warn("尝试发送停止指令到空的WebSocket会话");
            return;
        }

        String sessionId = session.getId();

        // 发送停止指令
        messageService.sendMessage(session, "tts", "stop");

        // 清空音频队列
        Queue<ProcessedAudioTask> queue = audioQueues.get(sessionId);
        if (queue != null) {
            // 保存需要删除的音频文件路径
            Queue<String> filesToDelete = new ConcurrentLinkedQueue<>();
            queue.forEach(task -> {
                if (task != null && task.getOriginalFilePath() != null) {
                    filesToDelete.add(task.getOriginalFilePath());
                }
            });

            // 清空队列
            queue.clear();

            // 异步删除文件
            if (!filesToDelete.isEmpty()) {
                audioPreprocessExecutor.submit(() -> {
                    filesToDelete.forEach(this::deleteAudioFiles);
                });
            }
        }

    }
}
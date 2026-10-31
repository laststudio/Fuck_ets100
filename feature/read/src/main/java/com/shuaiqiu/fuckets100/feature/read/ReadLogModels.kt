package com.shuaiqiu.fuckets100.feature.read

// ============================================================================
// 调试日志模型 - 用于跟踪应用内数据和答案的读取过程（从 ReadScreen 拆出）
// ============================================================================

/**
 * 日志级别枚举
 * 定义日志的重要程度和显示颜色
 */
internal enum class LogLevel(val label: String, val colorHex: Long) {
    DEBUG("调试", 0xFF60A5FA),      // 蓝色 - 调试信息
    INFO("信息", 0xFFFFD93D),       // 黄色 - 一般信息
    SUCCESS("成功", 0xFF2DD4BF),    // 青色 - 成功状态
    WARN("警告", 0xFFFB923C),       // 橙色 - 警告信息
    ERROR("错误", 0xFFFF6B6B),      // 红色 - 错误信息
    INIT("初始化", 0xFFE879F9)      // 紫色 - 初始化相关
}

/**
 * 日志类别枚举
 * 用于分组显示不同类型的数据
 */
internal enum class LogCategory(val label: String) {
    SYSTEM("系统"),
    FILE("文件"),
    PAPER("试卷"),
    SECTION("分区"),
    QUESTION("题目"),
    ANSWER("答案")
}

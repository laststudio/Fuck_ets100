package com.shuaiqiu.fuckets100

/**
 * 远程配置数据类
 *
 * @param latestVersionCode 最新版本号
 * @param updateUrl 新版下载地址
 * @param isKillSwitchOn 远程锁定开关
 * @param isForce 是否强制更新
 * @param updateMessage 更新弹窗正文内容
 * @param noticeMessage 启动时的 Toast 公告
 * @param announcementTitle 首页公告标题
 * @param announcementMessage 首页公告/公告详情正文
 * @param announcementUpdatedAt 公告更新时间
 * @param announcementUrl 公告详情远程地址
 * @param changelogUrl 更新日志远程地址
 * @param changelogTitle 首页更新日志标题
 * @param changelogSummary 首页更新日志摘要
 * @param verificationCode 启动验证验证码，留空表示不启用
 * @param verificationTitle 验证弹窗标题
 * @param verificationMessage 验证弹窗提示
 * @param donateEnabled 是否展示捐赠入口
 */
data class RemoteConfig(
    val latestVersionCode: Int,
    val updateUrl: String,
    val isKillSwitchOn: Boolean,
    val isForce: Boolean,
    val updateMessage: String,
    val noticeMessage: String,
    val announcementTitle: String = "",
    val announcementMessage: String = "",
    val announcementUpdatedAt: String = "",
    val announcementUrl: String = "",
    val changelogUrl: String = "",
    val changelogTitle: String = "",
    val changelogSummary: String = "",
    val verificationCode: String = "",
    val verificationTitle: String = "",
    val verificationMessage: String = "",
    val donateEnabled: Boolean = true
)

/**
 * 更新状态数据类
 * 用于封装更新检查的返回结果
 */
data class UpdateStatus(
    val isKillSwitch: Boolean,      // 是否 KillSwitch 锁定
    val showDialog: Boolean,        // 是否显示更新弹窗
    val message: String,            // 弹窗内容（用于 UpdateDialog）
    val isForce: Boolean,           // 是否强制更新
    val updateUrl: String,          // 更新链接
    val noticeMessage: String,      // 公告内容（用于 Toast）
    val announcementTitle: String = "",
    val announcementMessage: String = "",
    val announcementUpdatedAt: String = "",
    val announcementUrl: String = "",
    val changelogUrl: String = "",
    val changelogTitle: String = "",
    val changelogSummary: String = "",
    val requiresVerification: Boolean = false,
    val verificationCode: String = "",
    val verificationTitle: String = "",
    val verificationMessage: String = "",
    val donateEnabled: Boolean = true
)

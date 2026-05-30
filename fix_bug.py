import pathlib
f = pathlib.Path(r'app\src\main\java\com\shuaiqiu\fuckets100\ReadScreen.kt')
c = f.read_text(encoding='utf-8')

old = '''else -> {
                            val countText = if (isDownloaded || paper.regionLabel != "\u4e91\u7aef") {
                                "${paper.sections.size} \u4e2a\u5206\u533a \u00b7 ${paper.sections.sumOf { it.questions.size }} \u9053\u9898\u76ee"
                            } else {
                                "\u9884\u8ba1 ${paper.sections.size} \u4e2a\u5206\u533a \u00b7 ${paper.sections.sumOf { it.questions.size }} \u4e2a\u5185\u5bb9"
                            }
                            Text(
                                text = countText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // \u5b9d\u8d1d\u663e\u793a\u4e0b\u8f7d\u65f6\u95f4\u55b5~
                            Text(
                                text = if (paper.downloadTime > 0) "\u4e0b\u8f7d\u65f6\u95f4: $downloadTimeStr" else "\u672a\u52a0\u8f7d",'''

new = '''else -> {
                            val currentPaper = if (isDownloaded) downloadedPapers[homeworkKey]?.firstOrNull() else null
                            val countText = if (currentPaper != null) {
                                "${currentPaper.sections.size} \u4e2a\u5206\u533a \u00b7 ${currentPaper.sections.sumOf { it.questions.size }} \u9053\u9898\u76ee"
                            } else {
                                "\u9884\u8ba1 ${paper.sections.size} \u4e2a\u5206\u533a \u00b7 ${paper.sections.sumOf { it.questions.size }} \u4e2a\u5185\u5bb9"
                            }
                            Text(
                                text = countText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // \u5b9d\u8d1d\u663e\u793a\u4e0b\u8f7d\u65f6\u95f4\u55b5~
                            Text(
                                text = if (currentPaper != null && currentPaper.downloadTime > 0) "\u4e0b\u8f7d\u65f6\u95f4: $downloadTimeStr" else "\u672a\u52a0\u8f7d",'''

if old in c:
    c = c.replace(old, new, 1)
    f.write_text(c, encoding='utf-8')
    print('OK replaced')
else:
    print('NOT FOUND')

package com.shuaiqiu.fuckets100.feature.read

/** `homework.py` STRUCTURE_TYPE_META（structure_type → (category, typeName)）。 */
internal val STRUCTURE_TYPE_META: Map<String, Pair<String, String>> = mapOf(
    "collector.read" to ("read_sentence" to "模仿朗读"),
    "collector.word" to ("read_sentence" to "词汇练习"),
    "collector.role" to ("role" to "听选信息"),
    "collector.choose" to ("choose" to "听后选择"),
    "collector.picture" to ("read_sentence" to "信息转述"),
    "collector.fill" to ("fill" to "听后记录"),
    "collector.3q5a" to ("read_sentence" to "三问五答"),
    "collector.repeat_essay" to ("read_sentence" to "短文复述"),
    "collector.repeat_dialogue" to ("read_sentence" to "短文复述"),
    "collector.dialogue" to ("dialogue" to "回答问题"),
)

package com.shuaiqiu.fuckets100

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PaperStore {
    private val papers = ConcurrentHashMap<String, ETS100AnswerReader.Paper>()

    fun put(paper: ETS100AnswerReader.Paper): String {
        val key = UUID.randomUUID().toString()
        papers[key] = paper
        return key
    }

    fun get(key: String?): ETS100AnswerReader.Paper? {
        return key?.let { papers[it] }
    }

    fun remove(key: String?) {
        if (key != null) {
            papers.remove(key)
        }
    }
}

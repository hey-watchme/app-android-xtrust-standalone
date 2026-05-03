package com.xtrust.standalone.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// In-memory store — replace with Room in the next iteration
class TranscriptRepository {
    private val _cards = MutableStateFlow<List<CardEntity>>(emptyList())
    private val _topics = MutableStateFlow<List<TopicEntity>>(emptyList())

    val cards: Flow<List<CardEntity>> = _cards.asStateFlow()
    val topics: Flow<List<TopicEntity>> = _topics.asStateFlow()

    private var nextCardId = 1L
    private var nextTopicId = 1L

    suspend fun saveCard(card: CardEntity): Long {
        val id = nextCardId++
        _cards.value = _cards.value + card.copy(id = id)
        return id
    }

    suspend fun saveTopic(topic: TopicEntity): Long {
        val id = nextTopicId++
        _topics.value = _topics.value + topic.copy(id = id)
        return id
    }
}

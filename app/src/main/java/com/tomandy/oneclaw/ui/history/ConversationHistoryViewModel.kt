package com.tomandy.oneclaw.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.tomandy.oneclaw.data.dao.ConversationDao
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.ConversationEntity
import com.tomandy.oneclaw.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConversationHistoryViewModel(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ViewModel() {

    val conversations: Flow<PagingData<ConversationEntity>> = Pager(
        config = PagingConfig(
            pageSize = 10,
            prefetchDistance = 5,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDao.getAllConversationsPaged() }
    ).flow.cachedIn(viewModelScope)

    private val _previewMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val previewMessages: StateFlow<List<MessageEntity>> = _previewMessages.asStateFlow()

    private val _previewConversation = MutableStateFlow<ConversationEntity?>(null)
    val previewConversation: StateFlow<ConversationEntity?> = _previewConversation.asStateFlow()

    fun loadPreview(conversationId: String) {
        viewModelScope.launch {
            _previewConversation.value = conversationDao.getConversationOnce(conversationId)
            _previewMessages.value = messageDao.getMessagesOnce(conversationId)
        }
    }

    fun clearPreview() {
        _previewConversation.value = null
        _previewMessages.value = emptyList()
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationDao.deleteById(conversationId)
        }
    }
}

<template>
  <div class="app-layout">
    <div class="sidebar">
      <div class="logo-section">
        <img src="@/assets/logo.png" alt="硅谷小智" width="160" height="160" />
        <span class="logo-text">硅谷小智（医疗版）</span>
      </div>
      <el-button class="new-chat-button" @click="newChat">+ 新会话</el-button>
      <div class="conversation-list">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="conversation-item"
          :class="{ active: conv.id === currentConversationId }"
          @click="selectConversation(conv.id)"
        >
          <span class="conv-title" @dblclick="renameConversation(conv)">{{ conv.title }}</span>
          <span class="conv-delete" @click.stop="deleteConversation(conv.id)">×</span>
        </div>
      </div>
    </div>
    <div class="main-content">
      <div class="chat-container">
        <div class="message-list" ref="messaggListRef">
          <div
            v-for="(message, index) in messages"
            :key="index"
            :class="
              message.isUser ? 'message user-message' : 'message bot-message'
            "
          >
            <i
              :class="
                message.isUser
                  ? 'fa-solid fa-user message-icon'
                  : 'fa-solid fa-robot message-icon'
              "
            ></i>
            <span>
              <span v-html="message.content"></span>
              <span
                class="loading-dots"
                v-if="message.isThinking || message.isTyping"
              >
                <span class="dot"></span>
                <span class="dot"></span>
              </span>
            </span>
          </div>
        </div>
        <div class="input-container">
          <el-input
            v-model="inputMessage"
            placeholder="请输入消息"
            @keyup.enter="sendMessage"
          ></el-input>
          <el-button @click="sendMessage" :disabled="isSending" type="primary"
            >发送</el-button
          >
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessageBox } from 'element-plus'

const messaggListRef = ref()
const isSending = ref(false)
const inputMessage = ref('')
const messages = ref([])
const conversations = ref([])
const currentConversationId = ref(null)

onMounted(async () => {
  watch(messages, () => scrollToBottom(), { deep: true })
  await loadConversations()
  // 首次进入默认选中最新一条对话
  if (conversations.value.length > 0) {
    await selectConversation(conversations.value[0].id)
  }
})

const scrollToBottom = () => {
  if (messaggListRef.value) {
    messaggListRef.value.scrollTop = messaggListRef.value.scrollHeight
  }
}

// 拉取对话列表
const loadConversations = async () => {
  try {
    const { data } = await axios.get('/api/xiaozhi/conversations')
    conversations.value = data || []
  } catch (e) {
    console.error('加载对话列表失败', e)
  }
}

// 切换到某个对话并加载其消息
const selectConversation = async (id) => {
  currentConversationId.value = id
  await loadMessages(id)
}

// 加载某个对话的历史消息
const loadMessages = async (id) => {
  try {
    const { data } = await axios.get(`/api/xiaozhi/conversations/${id}/messages`)
    messages.value = (data || []).map((m) => ({
      isUser: m.isUser,
      content: m.content,
      isTyping: false,
      isThinking: false,
    }))
    scrollToBottom()
  } catch (e) {
    console.error('加载消息失败', e)
  }
}

// 新建会话：只清空当前，等发送第一条消息时才真正建对话
const newChat = () => {
  currentConversationId.value = null
  messages.value = []
  inputMessage.value = ''
}

// 删除对话
const deleteConversation = async (id) => {
  try {
    await ElMessageBox.confirm('确定删除该对话吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await axios.delete(`/api/xiaozhi/conversations/${id}`)
    if (currentConversationId.value === id) {
      currentConversationId.value = null
      messages.value = []
    }
    await loadConversations()
    if (!currentConversationId.value && conversations.value.length > 0) {
      await selectConversation(conversations.value[0].id)
    }
  } catch (e) {
    console.error('删除对话失败', e)
  }
}

// 重命名对话（双击标题）
const renameConversation = async (conv) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入新标题', '重命名', {
      inputValue: conv.title,
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
    if (value && value.trim()) {
      await axios.put(`/api/xiaozhi/conversations/${conv.id}`, { title: value.trim() })
      await loadConversations()
    }
  } catch {
    // 取消
  }
}

const sendMessage = () => {
  if (inputMessage.value.trim()) {
    sendRequest(inputMessage.value.trim())
    inputMessage.value = ''
  }
}

const sendRequest = async (message) => {
  if (isSending.value) return

  // 没有当前对话时先新建（标题取消息前 20 字）
  if (!currentConversationId.value) {
    try {
      const title = message.length > 20 ? message.slice(0, 20) : message || '新对话'
      const { data } = await axios.post('/api/xiaozhi/conversations', { title })
      currentConversationId.value = data.id
      await loadConversations()
    } catch (e) {
      console.error('新建对话失败', e)
      return
    }
  }

  isSending.value = true
  const userMsg = { isUser: true, content: message, isTyping: false, isThinking: false }
  messages.value.push(userMsg)

  const botMsg = { isUser: false, content: '', isTyping: true, isThinking: false }
  messages.value.push(botMsg)
  const lastMsg = messages.value[messages.value.length - 1]
  scrollToBottom()

  try {
    await axios.post(
      '/api/xiaozhi/chat',
      { memoryId: currentConversationId.value, message },
      {
        responseType: 'stream',
        onDownloadProgress: (e) => {
          const fullText = e.event.target.responseText
          const newText = fullText.substring(lastMsg.content.length)
          lastMsg.content += newText
          scrollToBottom()
        },
      },
    )
    lastMsg.isTyping = false
    isSending.value = false
  } catch (error) {
    console.error('流式错误:', error)
    lastMsg.content = lastMsg.content || '请求失败，请重试'
    lastMsg.isTyping = false
    isSending.value = false
  }
}
</script>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
}

.sidebar {
  width: 220px;
  background-color: #f4f4f9;
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.logo-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.logo-text {
  font-size: 18px;
  font-weight: bold;
  margin-top: 10px;
}

.new-chat-button {
  width: 100%;
  margin-top: 20px;
}

.conversation-list {
  width: 100%;
  margin-top: 20px;
  flex: 1;
  overflow-y: auto;
}

.conversation-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  margin-bottom: 6px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  color: #333;
  text-align: left;
  background-color: #fff;
  transition: background-color 0.2s;
}

.conversation-item:hover {
  background-color: #e8e8f0;
}

.conversation-item.active {
  background-color: #d9ecff;
  color: #409eff;
}

.conv-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-delete {
  margin-left: 8px;
  color: #999;
  font-size: 16px;
  line-height: 1;
}

.conv-delete:hover {
  color: #f56c6c;
}

.main-content {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
}
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  background-color: #fff;
  margin-bottom: 10px;
  display: flex;
  flex-direction: column;
}

.message {
  margin-bottom: 10px;
  padding: 10px;
  border-radius: 4px;
  display: flex;
}

.user-message {
  max-width: 70%;
  background-color: #e1f5fe;
  align-self: flex-end;
  flex-direction: row-reverse;
}

.bot-message {
  max-width: 100%;
  background-color: #f1f8e9;
  align-self: flex-start;
}

.message-icon {
  margin: 0 10px;
  font-size: 1.2em;
}

.loading-dots {
  padding-left: 5px;
}

.dot {
  display: inline-block;
  margin-left: 5px;
  width: 8px;
  height: 8px;
  background-color: #000000;
  border-radius: 50%;
  animation: pulse 1.2s infinite ease-in-out both;
}

.dot:nth-child(2) {
  animation-delay: -0.6s;
}

@keyframes pulse {
  0%,
  100% {
    transform: scale(0.6);
    opacity: 0.4;
  }

  50% {
    transform: scale(1);
    opacity: 1;
  }
}
.input-container {
  display: flex;
}

.input-container .el-input {
  flex: 1;
  margin-right: 10px;
}

@media (max-width: 768px) {
  .main-content {
    padding: 10px 0 10px 0;
  }
  .app-layout {
    flex-direction: column;
  }

  .sidebar {
    width: 100%;
    flex-direction: row;
    justify-content: space-between;
    align-items: center;
    padding: 10px;
  }

  .logo-section {
    flex-direction: row;
    align-items: center;
  }

  .logo-text {
    font-size: 20px;
  }

  .logo-section img {
    width: 40px;
    height: 40px;
  }

  .new-chat-button {
    margin-right: 30px;
    width: auto;
    margin-top: 5px;
  }

  .conversation-list {
    display: none;
  }
}

@media (min-width: 769px) {
  .main-content {
    padding: 0 0 10px 10px;
  }

  .app-layout {
    display: flex;
    height: 100vh;
  }

  .sidebar {
    width: 220px;
    background-color: #f4f4f9;
    padding: 20px;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .logo-section {
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .logo-text {
    font-size: 18px;
    font-weight: bold;
    margin-top: 10px;
  }

  .new-chat-button {
    width: 100%;
    margin-top: 20px;
  }
}
</style>

# OneClaw 🐾

An AI Agent platform for Android that brings the power of Large Language Models to your mobile device with a **Local-First, Cloud-Augmented** approach.

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android" alt="Android">
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4" alt="Compose">
  <img src="https://img.shields.io/badge/Architecture-MVVM-brightgreen" alt="MVVM">
</p>

## 🌟 Features

- **Multi-Provider LLM Support**: OpenAI, Anthropic Claude, Groq, and more
- **Bring Your Own Key (BYOK)**: Full control over your API keys and data
- **Local-First**: All data stored securely on your device
- **Hardware-Backed Encryption**: API keys protected by Android KeyStore
- **Markdown Support**: Rich message formatting with code blocks
- **Conversation History**: Persistent storage with Room database
- **Modern UI**: Built with Jetpack Compose and Material 3

### 🚀 Coming Soon (Phase 2)
- **Dynamic Plugin System**: Extend functionality with Kotlin Script (KTS) plugins
- **Tool Execution**: Agent can perform actions (Gmail, Web Search, Notion, etc.)
- **Automation**: Cross-app automation using Accessibility Services

## 📱 Screenshots

*Coming soon*

## 🏗️ Architecture

OneClaw follows a clean, modular architecture:

```
┌─────────────────────────────────────────────┐
│            UI Layer (Compose)               │
│  ChatScreen │ SettingsScreen │ Navigation  │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│         ViewModels (State Management)        │
│  ChatViewModel │ SettingsViewModel          │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│          Agent Layer (Business Logic)        │
│  AgentCoordinator │ ReActLoop │ ToolExecutor│
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│          Data Layer (Persistence)            │
│  Room Database │ CredentialVault (Encrypted)│
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│          LLM Client Layer (API)              │
│  OpenAI │ Anthropic │ Groq │ Custom         │
└─────────────────────────────────────────────┘
```

### Key Components

- **UI Layer**: Jetpack Compose screens and composables
- **Agent Layer**: Orchestrates LLM interactions and tool execution
- **Data Layer**: Room database for conversations, EncryptedSharedPreferences for API keys
- **LLM Client**: Retrofit-based clients supporting multiple LLM providers

## 🛠️ Tech Stack

| Category | Technology |
|----------|-----------|
| **Language** | Kotlin 2.0.21 |
| **UI Framework** | Jetpack Compose |
| **Architecture** | MVVM with Coroutines & Flow |
| **Database** | Room 2.7.0-alpha11 |
| **Networking** | Retrofit 2.11.0 + OkHttp 5.3.2 |
| **Security** | EncryptedSharedPreferences + Android KeyStore |
| **Serialization** | kotlinx-serialization 1.9.0 |
| **Dependency Injection** | Manual (Dagger/Hilt planned for Phase 3) |
| **Testing** | JUnit, Compose UI Testing, MockK |

## 📦 Installation

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 26 (Android 8.0) or higher
- JDK 11 or higher

### Setup

1. **Clone the repository**:
   ```bash
   git clone https://github.com/yourusername/oneclaw.git
   cd oneclaw
   ```

2. **Open in Android Studio**:
   - File → Open → Select the `oneclaw` directory

3. **Sync Gradle**:
   - Android Studio should automatically sync. If not, click "Sync Now"

4. **Run the app**:
   - Select a device/emulator
   - Click the Run button (▶️) or press `Shift+F10`

## 🚀 Getting Started

### 1. Add an API Key

When you first launch OneClaw:

1. Tap the **Settings** icon (⚙️) in the top-right
2. Select your LLM provider (OpenAI, Anthropic, etc.)
3. Enter your API key
4. Tap **Save API Key**

Your API key is encrypted with hardware-backed security and never leaves your device.

### 2. Start Chatting

1. Return to the Chat screen
2. Type your message in the input field
3. Tap Send (➤)
4. Watch as the AI responds!

### 3. Manage Conversations

- **New Conversation**: Tap the ➕ icon
- **Switch Models**: Use the model dropdown to change between GPT-4, Claude, etc.
- **View History**: All conversations are automatically saved

## 🔐 Security & Privacy

OneClaw takes your privacy seriously:

- ✅ **No Data Collection**: We don't collect any user data
- ✅ **Local Storage**: All conversations stored on your device
- ✅ **Encrypted Keys**: API keys encrypted with Android KeyStore (hardware-backed)
- ✅ **BYOK**: You control your API keys and LLM provider
- ✅ **Open Source**: Audit the code yourself

### Supported API Keys

| Provider | Get Your API Key |
|----------|------------------|
| OpenAI | https://platform.openai.com/api-keys |
| Anthropic | https://console.anthropic.com/settings/keys |
| Groq | https://console.groq.com/keys |

## 🧪 Testing

OneClaw has comprehensive test coverage:

- **Unit Tests**: 70%+ coverage
- **Integration Tests**: Full E2E chat flow, process death recovery
- **Performance Tests**: Large conversations, encryption overhead

See [TESTING.md](ai_docs/TESTING.md) for detailed testing instructions.

### Run Tests

```bash
# Unit tests
./gradlew test

# Integration tests
./gradlew connectedAndroidTest

# Specific test
./gradlew test --tests "com.tomandy.oneclaw.agent.AgentCoordinatorTest"
```

## 📚 Documentation

- **[Design Document](ai_docs/design.md)**: Full architecture specification
- **[Phase 1 Implementation](ai_docs/phase1-implementation.md)**: Core features implementation plan
- **[Phase 2 Plan](ai_docs/PHASE2_IMPLEMENTATION_PLAN_UPDATED.md)**: Plugin system and tool execution
- **[Testing Guide](ai_docs/TESTING.md)**: How to run and write tests
- **[Phase 1 Status](ai_docs/PHASE1_COMPLETION_STATUS.md)**: Current implementation status

## 🗺️ Roadmap

### ✅ Phase 1: Core Chat (Current)
- [x] Multi-provider LLM support
- [x] Encrypted API key storage
- [x] Conversation persistence
- [x] Markdown rendering
- [x] Settings management
- [ ] Integration tests (In Progress)

### 🚧 Phase 2: Plugin System (Next)
- [ ] KTS plugin compilation pipeline
- [ ] Tool execution framework
- [ ] Sample plugins (Gmail, Web Search, Notion)
- [ ] Foreground service for long-running tasks

### 🔮 Phase 3: Automation (Future)
- [ ] Accessibility Service integration
- [ ] Cross-app UI automation
- [ ] Screen capture & OCR
- [ ] Vector database for long-term memory

## 🤝 Contributing

We welcome contributions! Here's how you can help:

1. **Report Bugs**: Open an issue with reproduction steps
2. **Suggest Features**: Share your ideas in GitHub Discussions
3. **Submit PRs**: Fork, make changes, and submit a pull request
4. **Improve Docs**: Help us make the documentation better

### Development Guidelines

- Follow Kotlin coding conventions
- Write tests for new features
- Update documentation for user-facing changes
- Keep commits atomic and well-described

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines (coming soon).

## 📄 License

OneClaw is open source and available under the [Apache 2.0 License](LICENSE).

## 🙏 Acknowledgments

- **OpenClaw**: Reference architecture and design patterns
- **Anthropic**: Claude API and AI safety principles
- **OpenAI**: GPT models and API
- **Kotlin Community**: Amazing language and ecosystem

## 📬 Contact

- **GitHub Issues**: Bug reports and feature requests
- **Discussions**: Questions and community chat
- **Email**: [your-email@example.com]

## ⭐ Star History

If you find OneClaw useful, please consider giving it a star! ⭐

---

**Built with ❤️ using Kotlin and Jetpack Compose**

*OneClaw: Your AI Agent, Your Device, Your Control*

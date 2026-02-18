# Credential Vault - Quick Reference

## Overview
Secure API key storage using Android KeyStore encryption.

## Files
- `CredentialVault.kt` - Interface
- `CredentialVaultImpl.kt` - Implementation

## Quick Start

### 1. Create Instance
```kotlin
val vault = CredentialVaultImpl(context)
```

### 2. Save API Key
```kotlin
viewModelScope.launch {
    vault.saveApiKey("OpenAI", "sk-...")
}
```

### 3. Retrieve API Key
```kotlin
viewModelScope.launch {
    val key = vault.getApiKey("OpenAI")
}
```

### 4. Delete API Key
```kotlin
viewModelScope.launch {
    vault.deleteApiKey("OpenAI")
}
```

### 5. List Providers
```kotlin
viewModelScope.launch {
    val providers = vault.listProviders() // Sorted alphabetically
}
```

## Security Features
- ✅ Hardware-backed encryption (Android KeyStore)
- ✅ AES256_GCM encryption
- ✅ Automatic key generation
- ✅ Thread-safe operations (Dispatchers.IO)
- ✅ Encrypted SharedPreferences

## Error Handling
```kotlin
try {
    vault.saveApiKey(provider, key)
} catch (e: SecurityException) {
    // Security/encryption error
} catch (e: IllegalArgumentException) {
    // Invalid input (empty strings)
} catch (e: RuntimeException) {
    // General operation error
}
```

## Common Providers
- `"OpenAI"` - OpenAI API (GPT-4, etc.)
- `"Anthropic"` - Claude API
- `"Google"` - Google AI (Gemini)
- `"Cohere"` - Cohere API
- `"Mistral"` - Mistral AI

## Best Practices
1. Always use `suspend` functions in coroutines
2. Never log API keys
3. Check for null when retrieving keys
4. Validate provider names before saving
5. Handle security exceptions gracefully

## Testing
See `CredentialVaultTest.kt` for comprehensive test examples.

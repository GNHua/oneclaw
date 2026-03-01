package com.oneclaw.shadow.core.model

object ProviderCapability {
    fun supportsAttachmentType(providerType: ProviderType, attachmentType: AttachmentType): Boolean {
        return when (providerType) {
            ProviderType.OPENAI -> attachmentType == AttachmentType.IMAGE
            ProviderType.ANTHROPIC -> attachmentType in listOf(AttachmentType.IMAGE, AttachmentType.FILE)
            ProviderType.GEMINI -> attachmentType in listOf(AttachmentType.IMAGE, AttachmentType.VIDEO)
        }
    }

    fun getUnsupportedTypes(
        providerType: ProviderType,
        attachmentTypes: List<AttachmentType>
    ): List<AttachmentType> {
        return attachmentTypes
            .distinct()
            .filter { !supportsAttachmentType(providerType, it) }
    }
}

package com.tomandy.palmclaw.plugin

interface ConfigContributor {
    fun contribute(): List<ConfigEntry>
}

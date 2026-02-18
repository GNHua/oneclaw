package com.tomandy.oneclaw.plugin

interface ConfigContributor {
    fun contribute(): List<ConfigEntry>
}

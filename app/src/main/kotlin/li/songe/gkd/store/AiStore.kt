package li.songe.gkd.store

/** Stored outside normal settings backups and diagnostic log exports. */
val deepSeekApiKeyFlow by lazy {
    createTextFlow(
        key = "deepseek_api_key",
        decode = { it?.trim() ?: "" },
        encode = { it.trim() },
        private = true,
    )
}

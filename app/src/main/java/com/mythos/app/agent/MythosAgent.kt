package com.mythos.app.agent

import java.io.File

/** Pintu masuk tunggal untuk membuat agen. */
object MythosAgent {
    fun create(
        client: LlmClient,
        projectDir: File,
        approver: Approver,
        ask: suspend (String) -> String,
        builder: BuildService? = null,
        config: AgentConfig = AgentConfig(),
        journal: TaskJournal? = null,
        withWeb: Boolean = true,
        searchKeys: SearchKeys = SearchKeys(),
        device: DeviceActions? = null,
        workspace: GoogleWorkspaceService? = null,
    ): Agent {
        projectDir.mkdirs()
        val sandbox = ProjectSandbox(projectDir)
        val registry = ToolRegistry.default(
            builder != null,
            withPlan = config.permissionMode == PermissionMode.PLAN,
            withWeb = withWeb,
            subAgentLlm = client,
            searchKeys = searchKeys,
            withDevice = device != null,
            withWorkspace = workspace != null,
            withGmail = workspace?.gmailEnabled == true,
            withCalendar = workspace?.calendarEnabled == true,
            withDrive = workspace?.driveEnabled == true,
        )
        return Agent(client, registry, sandbox, approver, ask, builder, config, journal, device, workspace)
    }

    fun create(
        provider: ProviderConfig,
        projectDir: File,
        approver: Approver,
        ask: suspend (String) -> String,
        builder: BuildService? = null,
        config: AgentConfig = AgentConfig(),
        journal: TaskJournal? = null,
        withWeb: Boolean = true,
        searchKeys: SearchKeys = SearchKeys(),
        device: DeviceActions? = null,
        workspace: GoogleWorkspaceService? = null,
    ): Agent = create(createClient(provider), projectDir, approver, ask, builder, config, journal, withWeb, searchKeys, device, workspace)
}

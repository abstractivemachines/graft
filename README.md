# Graft

[![Build](https://github.com/abstractivemachines/graft/actions/workflows/build.yml/badge.svg)](https://github.com/abstractivemachines/graft/actions/workflows/build.yml)

<!-- Plugin description -->
Graft lets an MCP client such as Claude Code attach a git worktree to the current JetBrains IDE window and detach it again, the two things the IDE only offers through the mouse: <b>File | Open | Attach</b> and <b>Remove from Project View</b>.

<h3>What it adds</h3>
<ul>
  <li><b>attach_worktree</b> attaches a directory as an additional module of the open project. A directory without <code>.idea</code> gets one, named after the directory. It refuses up front, with an explanation, when a copied <code>.idea</code> would make the IDE fail with "Module name already exists", or when the directory is outside a trusted location.</li>
  <li><b>detach_worktree</b> removes an attached module and the dependency the primary module holds on it, and can delete the worktree's own <code>.idea</code> folder so the next worktree with that name does not collide.</li>
  <li><b>list_attached_worktrees</b> lists the modules in the window with their paths, linked-worktree status, checked-out branch, and owning repository.</li>
</ul>

<h3>How it works</h3>
<p>Graft has no server, port, or configuration of its own. It contributes its tools through the bundled MCP Server plugin's own extension point, <code>com.intellij.mcpServer.mcpToolset</code>, so they appear in the same tool list as the IDE's built-in tools, follow the same allow list under <b>Settings | Tools | MCP Server | Exposed Tools</b>, and work from any client already connected to the IDE. Under the tools it calls the platform code behind the menu actions: the <code>ProjectAttachProcessor</code> the Open dialog uses for attach, and the module model's modifiable API for detach. Nothing is written to disk that the equivalent menu action would not write.</p>

<h3>The workflow it is built for</h3>
<p>The main checkout stays open in one window. An agent creates a worktree per ticket, attaches it, works in it, and detaches it when the branch merges:</p>
<pre>Start issue 42: create a worktree off origin/main at ~/src/.worktrees/myapp/issue-42-login-form,
attach it to the myapp window, and run yarn install in it.</pre>
<pre>Issue 42 merged. Detach its worktree and delete the .idea it left, remove the worktree, and delete the branch.</pre>
<p>Detach before <code>git worktree remove</code>: the IDE drops the module and VCS mapping cleanly, and the directory is empty afterwards. Every tool accepts the MCP Server's <code>projectPath</code> argument to pick the window when several projects are open.</p>

<h3>Requirements</h3>
<p>A JetBrains IDE from 2026.2 (build 262) or newer with the bundled MCP Server plugin enabled, and an MCP client connected to it. Restart the client after installing so it picks up the new tools.</p>

<p>
<a href="https://github.com/abstractivemachines/graft">Source code</a> ·
<a href="https://github.com/abstractivemachines/graft/issues">Issue tracker</a> ·
<a href="https://github.com/abstractivemachines/graft/blob/main/CHANGELOG.md">Changelog</a>
</p>
<!-- Plugin description end -->

## Why

JetBrains IDEs can hold several directories in one window through Attach, which is ideal for git worktrees: the
main checkout stays open and each worktree shows up beside it with shared run configurations, terminal, and
search. The catch is that Attach and its opposite exist only in the UI. There is no command-line flag, action id
that an external tool can trigger, or MCP tool for either. Every worktree plugin on the Marketplace opens
worktrees in a new window instead. This plugin closes that gap by exposing the platform's own attach and detach
code as MCP tools.

## How it works

Graft has no server, port, or configuration of its own. Since 2025.2 every JetBrains IDE bundles the **MCP Server**
plugin, which serves the IDE's tools to clients such as Claude Code over HTTP, SSE, or stdio. That plugin declares an
extension point, `com.intellij.mcpServer.mcpToolset`, for other plugins to contribute tools. Graft implements it and
nothing else:

```xml
<depends>com.intellij.mcpServer</depends>
<extensions defaultExtensionNs="com.intellij">
    <mcpServer.mcpToolset implementation="com.abstractivemachines.graft.GraftToolset"/>
</extensions>
```

`GraftToolset` is a class implementing the server's `McpToolset` interface. Each tool is a `suspend` function marked
`@McpTool`, with `@McpDescription` on the function and on every parameter. The MCP Server does the rest the same way
it does for its own tools: it reflects the function signature into the tool's JSON schema, adds the optional
`projectPath` argument and resolves it to an open project, deserializes the call arguments, serializes the returned
data class into `structuredContent`, and turns a thrown `McpExpectedError` (raised via `mcpFail`) into an MCP error
result. Because Graft goes through the sanctioned extension point rather than a private hook, its tools show up in
the same tool list, obey the same allow list under **Settings | Tools | MCP Server | Exposed Tools**, and work from
any client already connected to the IDE. Users can switch the *Graft* group on or off there like any other toolset.

Under the tools, Graft calls the platform code behind the two menu actions it replaces:

- **Attach** asks the platform which `ProjectAttachProcessor` accepts the directory, exactly as the Open dialog does,
  and calls its `attachToProjectAsync`. The processor loads the directory's module file into the project, adds a
  dependency from the primary module to the new one, and fires the attach listeners that register the directory as a
  VCS root. A directory with no `.idea` is scaffolded first, producing `.idea/<dirname>.iml`, so a fresh worktree
  attaches with the module named after its folder. Before calling the platform, Graft rejects the cases that would
  otherwise stop on a modal dialog: a relative path, a directory outside a trusted location, a copied `.idea` whose
  module name is already taken, and a `.idea` with no module file at all.
- **Detach** reproduces *Remove from Project View* with the public module-model API: it opens a modifiable model,
  removes every order entry other modules hold on the target module, notifies the attach processors so the VCS
  mapping is dropped, disposes the module, and commits everything in one write command. Optionally it then deletes
  the worktree's `.idea` folder, which the IDE leaves behind and which is the usual source of "Module name already
  exists" on the next worktree with the same name.
- **List** reads the project's modules and their content roots, and for each root parses the `.git` pointer file
  and `HEAD` of a linked worktree to report the branch and owning repository.

Everything runs inside the IDE process under its normal threading rules: read actions for inspection, a write command
on the EDT for detach, and the platform's own coroutine for attach. Nothing is written to disk that the equivalent
menu action would not write.

## Requirements

- A JetBrains IDE from the 2026.2 line (build 262) or newer with the bundled **MCP Server** plugin enabled.
  Built and verified against WebStorm 2026.2; other IntelliJ-based IDEs that support Attach (PyCharm, PhpStorm,
  RubyMine, GoLand, DataGrip, Rider) should work but have not been tried.
- An MCP client connected to the IDE. For Claude Code: **Settings | Tools | MCP Server**, then **Auto-Configure**
  next to Claude Code, or copy the HTTP config from that page into `~/.claude.json`.

## Install

Pick one. Afterwards restart the IDE, then restart the MCP client so it picks up the new tool list.
The tools appear under **Settings | Tools | MCP Server | Exposed Tools** as *Graft*.

**JetBrains Marketplace (recommended).** In the IDE open **Settings | Plugins**, search for *Graft* in the
**Marketplace** tab, and install it. Or install from the web at
[plugins.jetbrains.com/plugin/34190-graft](https://plugins.jetbrains.com/plugin/34190-graft).

**Custom plugin repository.** For machines that cannot reach the Marketplace, or to pick up a build before it is
approved there. In the IDE open
**Settings | Plugins | ⚙ | Manage Plugin Repositories**, add

```
https://raw.githubusercontent.com/abstractivemachines/graft/main/updatePlugins.xml
```

then search for *Graft* in the **Marketplace** tab and install it. The IDE checks this file for new
versions the same way it checks the Marketplace.

**One-line install from a terminal (macOS, Linux).** Installs the latest GitHub release into every JetBrains IDE
2026.2 or newer found on the machine:

```
curl -fsSL https://raw.githubusercontent.com/abstractivemachines/graft/main/install.sh | bash
```

**Install from disk.** Download `graft-<version>.zip` from
[Releases](https://github.com/abstractivemachines/graft/releases), or build it with `./gradlew buildPlugin`
(it lands in `build/distributions/`), then **Settings | Plugins | ⚙ | Install Plugin from Disk...**.

## Tools

| Tool | Arguments | What it does |
|---|---|---|
| `attach_worktree` | `path` | Attach the directory to the project window. Returns `attached` or `already_attached` and the resulting module list. |
| `detach_worktree` | `path`, `deleteIdeaDirectory` (default `false`) | Detach the module found by path or module name. Refuses to detach the primary module. |
| `list_attached_worktrees` | none | Modules in the window, each with `path`, `primary`, `linkedWorktree`, `branch`, `mainRepository`. |

All three accept the MCP Server's optional `projectPath` argument to pick the window when several projects are
open. Paths may start with `~`.

Example prompts once the tools are connected:

```
Create a worktree for issue 42 off origin/main at ~/src/.worktrees/myapp/issue-42-login-form and attach it.
```

```
The PR for issue 42 merged. Detach the worktree, delete its .idea, remove the worktree and the branch.
```

## Using Graft effectively

Graft is built around one workflow: the main checkout of a repository stays open in one IDE window, and each piece of
work happens in a git worktree that is attached to that window while it is active and detached when it merges. The
agent drives the whole cycle; the IDE follows.

### Prerequisites

1. **Settings | Tools | MCP Server** is enabled, and your client is connected. For Claude Code press
   **Auto-Configure** on that page, or copy the HTTP config it shows into `~/.claude.json`.
2. Your worktree root is a trusted location (**Settings | Build, Execution, Deployment | Trusted Locations**), for
   example `~/src`. Otherwise `attach_worktree` refuses rather than blocking on the trust dialog.
3. Both the IDE and the MCP client were restarted after installing Graft. The client caches the tool list.

Confirm the tools are visible by asking the client something that needs them:

```
Which worktrees are attached to the myapp window?
```

### The ticket cycle

Start of a ticket. The agent creates the worktree with git, then attaches it:

```
Start issue 42: create a worktree off origin/main at ~/src/.worktrees/myapp/issue-42-login-form
with branch issue-42-login-form, attach it to the myapp window, and run yarn install in it.
```

Behind that request the agent runs `git worktree add`, then calls

```json
attach_worktree { "path": "~/src/.worktrees/myapp/issue-42-login-form" }
```

and gets back the module name and the new module list:

```json
{
  "status": "attached",
  "moduleName": "issue-42-login-form",
  "path": "/Users/you/src/.worktrees/myapp/issue-42-login-form",
  "modules": [
    { "name": "myapp", "path": "/Users/you/src/myapp", "primary": true, "linkedWorktree": false },
    { "name": "issue-42-login-form", "path": "/Users/you/src/.worktrees/myapp/issue-42-login-form",
      "primary": false, "linkedWorktree": true, "branch": "issue-42-login-form", "mainRepository": "/Users/you/src/myapp" }
  ]
}
```

The worktree now appears in the Project view beside `myapp`, shares the window's run configurations and terminal,
and is indexed and searchable together with the main checkout.

End of a ticket. Once the pull request has merged:

```
Issue 42 merged. Detach its worktree and delete the .idea it left, remove the worktree, and delete the branch.
```

The agent calls

```json
detach_worktree { "path": "issue-42-login-form", "deleteIdeaDirectory": true }
```

and then runs `git worktree remove` and `git branch -d`. The order matters: detaching first lets the IDE drop the
module and the VCS mapping cleanly, and deleting `.idea` before `git worktree remove` means the directory is actually
empty afterwards. Doing it the other way round leaves a module pointing at a missing path, and `.idea/<name>.iml`
lying in an otherwise deleted folder.

### Housekeeping

`list_attached_worktrees` is the audit tool. Ask for it whenever the Project view looks off:

```
List what is attached to the api window and flag anything whose directory no longer exists.
```

A module whose `path` is gone on disk, or whose `linkedWorktree` is false when it should be a worktree, is a leftover
from a manual removal. Detach it by module name. The same call is a cheap guard before attaching: the agent can check
that the directory is not already there rather than relying on the `already_attached` status.

### Several windows open

Every Graft tool accepts the MCP Server's `projectPath` argument. With one project open it is optional. With several,
the server returns an error listing the open projects, and the agent passes the right one:

```json
attach_worktree { "projectPath": "/Users/you/src/api", "path": "~/src/.worktrees/api/issue-42-login-endpoint" }
```

Attach into the window that owns the repository the worktree belongs to. The `mainRepository` field in the module
list shows the pairing after the fact.

### Teaching the agent the workflow

Put the cycle in the agent's instructions once so it is followed without prompting. A `CLAUDE.md` fragment:

```markdown
# Worktrees and the IDE
- Every change happens in a worktree at ~/src/.worktrees/<repo>/<BRANCH>, branched from origin/main.
- After creating a worktree, call attach_worktree with its path so it joins the repo's IDE window.
  Never copy .idea into a worktree; the IDE creates the module itself.
- Worktree directory names must be unique across all repos; the IDE module is named after the directory.
- When a PR merges: detach_worktree with deleteIdeaDirectory true, then git worktree remove, then delete the branch.
- Use list_attached_worktrees before attaching and when cleaning up stale modules.
```

### Things that will not work, by design

- Attaching the primary module's own directory, or detaching it. The tools refuse.
- Attaching a directory outside a trusted location. Add the parent to Trusted Locations and retry.
- Attaching a worktree that carries a copied `.idea` with the main checkout's module file. Delete that `.idea` and
  retry; the message tells you which module name collided.
- Two attached directories with the same folder name, even from different repositories. Rename one.
- Nesting a worktree inside the main checkout. The IDE indexes it as part of the parent project. Keep worktrees
  beside the repository, not inside it.

## Behaviour worth knowing

- **Trust.** Attaching a directory outside a trusted location makes the IDE show its trust dialog, and the tool
  call waits for the answer. Add your worktree root under **Settings | Build, Execution, Deployment | Trusted
  Locations** to avoid it.
- **Copied `.idea`.** Attach loads the module file it finds in the directory. If `.idea` was copied from the main
  checkout it carries that checkout's module name and the IDE refuses. `attach_worktree` detects this and fails
  with a message instead of leaving a modal dialog open. Leave new worktrees without `.idea`; the IDE creates one.
- **Detach cleans up.** The IDE adds a dependency from the primary module to each attached module.
  `detach_worktree` removes that order entry along with the module, and notifies the platform's attach listeners so
  the VCS root mapping goes away too.
- **API status.** Attaching calls `ProjectAttachProcessor.attachToProjectAsync`, the same entry point the Open
  dialog uses; JetBrains marks it experimental, so Plugin Verifier lists it as a warning. Detach uses only stable
  module-model API. No internal API is used.

## Development

```
./gradlew buildPlugin                                   # zip in build/distributions/
./gradlew test                                          # unit tests for the path helpers
./gradlew verifyPlugin                                  # IntelliJ Plugin Verifier
./gradlew runIde                                        # sandbox IDE with the plugin installed

# compile and verify against an installed IDE instead of downloading one
./gradlew buildPlugin verifyPlugin -PlocalIdePath=/Applications/WebStorm.app
```

**Releasing.** Bump `pluginVersion` in `gradle.properties`, move the `[Unreleased]` notes in `CHANGELOG.md` under
the new version, update the version and url in `updatePlugins.xml`, commit, then tag `vX.Y.Z` and push the tag.
The Release workflow builds, verifies, publishes to JetBrains Marketplace (needs the `PUBLISH_TOKEN` secret), and
attaches the zip to a GitHub release.

The project follows the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
layout: plugin metadata lives in `gradle.properties`, the plugin description is this README between the two
marker comments, and change notes come from `CHANGELOG.md`.

## License

[MIT](LICENSE) © Abstractive Machines LLC

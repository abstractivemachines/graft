# Worktree Attach MCP

[![Build](https://github.com/abstractivemachines/worktree-attach-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/abstractivemachines/worktree-attach-mcp/actions/workflows/build.yml)

<!-- Plugin description -->
Lets an MCP client such as Claude Code attach a git worktree to the current JetBrains IDE window and detach it again, the two things the IDE only offers through the mouse: <b>File | Open | Attach</b> and <b>Remove from Project View</b>.

The plugin adds three tools to the IDE's built-in MCP Server:

<ul>
  <li><b>attach_worktree</b> attaches a directory as an additional module of the open project. A directory without <code>.idea</code> gets one, named after the directory. It refuses up front, with an explanation, when a copied <code>.idea</code> would make the IDE fail with "Module name already exists".</li>
  <li><b>detach_worktree</b> removes an attached module and the dependency the primary module holds on it, and can delete the worktree's own <code>.idea</code> folder so the next worktree with that name does not collide.</li>
  <li><b>list_attached_worktrees</b> lists the modules in the window with their paths, linked-worktree status, and checked-out branch.</li>
</ul>

Built for the one-window-per-repo workflow: the main checkout stays open, an agent creates a worktree per ticket, attaches it, works in it, and detaches it when the branch merges.
<!-- Plugin description end -->

## Why

JetBrains IDEs can hold several directories in one window through Attach, which is ideal for git worktrees: the
main checkout stays open and each worktree shows up beside it with shared run configurations, terminal, and
search. The catch is that Attach and its opposite exist only in the UI. There is no command-line flag, action id
that an external tool can trigger, or MCP tool for either. Every worktree plugin on the Marketplace opens
worktrees in a new window instead. This plugin closes that gap by exposing the platform's own attach and detach
code as MCP tools.

## Requirements

- A JetBrains IDE from the 2026.2 line (build 262) or newer with the bundled **MCP Server** plugin enabled.
  Built and verified against WebStorm 2026.2; other IntelliJ-based IDEs that support Attach (PyCharm, PhpStorm,
  RubyMine, GoLand, DataGrip, Rider) should work but have not been tried.
- An MCP client connected to the IDE. For Claude Code: **Settings | Tools | MCP Server**, then **Auto-Configure**
  next to Claude Code, or copy the HTTP config from that page into `~/.claude.json`.

## Install

1. Download the latest `worktree-attach-mcp-<version>.zip` from
   [Releases](https://github.com/abstractivemachines/worktree-attach-mcp/releases), or build it with
   `./gradlew buildPlugin` (it lands in `build/distributions/`).
2. In the IDE: **Settings | Plugins | ⚙ | Install Plugin from Disk...** and pick the zip.
3. Restart the IDE. The tools appear under **Settings | Tools | MCP Server | Exposed Tools** as *Worktree Attach*.
4. Restart the MCP client so it picks up the new tool list.

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
Create a worktree for SW-1234 off origin/main at ~/src/.worktrees/device/SW-1234-slug and attach it.
```

```
The PR for SW-1234 merged. Detach the worktree, delete its .idea, remove the worktree and the branch.
```

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

The project follows the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
layout: plugin metadata lives in `gradle.properties`, the plugin description is this README between the two
marker comments, and change notes come from `CHANGELOG.md`.

## License

[MIT](LICENSE) © Abstractive Machines LLC

<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Graft Changelog

## [Unreleased]

## [0.1.1] - 2026-09-11

### Changed

- Marketplace listing and README now explain how Graft extends the bundled MCP Server through its `mcpToolset` extension point, what attach and detach do at the platform level, and how to run the worktree-per-ticket cycle with an agent.
- Install instructions lead with the JetBrains Marketplace, where Graft is now published.

## [0.1.0] - 2026-09-09

### Added

- `attach_worktree` MCP tool: attaches a directory to the current IDE window as a module, the same as File | Open | Attach, and refuses up front when a copied `.idea` would make the IDE fail with "Module name already exists".
- `detach_worktree` MCP tool: removes an attached module and the dependency the primary module holds on it, optionally deleting the worktree's `.idea` folder.
- `list_attached_worktrees` MCP tool: lists attached modules with their paths, linked-worktree status, and checked-out branch.

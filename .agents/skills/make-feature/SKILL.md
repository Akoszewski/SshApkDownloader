---
name: make-feature
description: Use when the user wants Codex to implement a feature or fix in this repository using a dedicated git branch, repo-style commits, build verification before every commit, and a final handoff for the user to open a pull request manually.
---

# Make Feature

Use this workflow for feature or fix implementation that should land through a pull request instead of direct work on `master`.

## Workflow

1. Inspect the request and current repo state.
   - Read relevant files before planning changes.
   - Check `git status --short --branch`.
   - If the working tree has unrelated user changes, preserve them and work around them.

2. Create a dedicated branch before editing.
   - Choose a short branch name that describes the requested work.
   - Prefer lowercase hyphenated names with a `codex/` prefix, for example `codex/add-apk-path-config`.
   - Do not work directly on `master` unless the user explicitly asks.

3. Implement the change using normal repo conventions.
   - Keep edits scoped to the requested feature or fix.
   - Follow existing architecture, naming, formatting, and UI patterns.
   - Add or update tests when the behavioral risk justifies it.
   - Find and update the repository's instruction Markdown file when the change adds or changes user-visible behavior. Add a concise description of the feature, configuration, or workflow so the instructions stay current. If no suitable instruction file exists, ask before creating a new one.

4. Commit in the repository's existing style and cadence.
   - Inspect recent commit history before the first commit.
   - Match the local style: short imperative subject lines such as `Add ...`, `Fix ...`, `Improve ...`, or `Move ...`.
   - Commit after coherent, buildable units of work rather than after every tiny edit.
   - Before every commit, run the project's build command. For this repo, prefer `./build.sh` when available; otherwise use the established Gradle build command.
   - Do not commit if the build fails. Fix the issue or report the blocker.

5. Push the branch when implementation and verification are complete.
   - Push only the feature branch, not `master`.
   - If branch protection requires pull requests, leave PR creation to the user unless they explicitly ask Codex to create it.

6. Final response.
   - State that the work is implemented.
   - Include the branch name.
   - Mention the build command that passed.
   - Mention the instruction Markdown file updated, when applicable.
   - Tell the user to open a pull request manually from that branch.

## Guardrails

- Never rewrite or discard user changes unless explicitly requested.
- Never force-push unless explicitly requested and the branch is known to be Codex-owned.
- Do not bypass branch protection.
- If committing or pushing requires elevated permissions, request the narrow command approval needed.

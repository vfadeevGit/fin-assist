<!-- BEGIN jmix-agent-toolkit -->
## Jmix

This is a Jmix 3 application. Before writing or changing ANY file in it, read
the `jmix` skill and follow it. It maps the task to artifacts, routes each
artifact to the skill that governs it, and names the checks that close a task.
Your Jmix/Vaadin priors are not reliable here; the skills are.

Managed by the Jmix Agent Toolkit. Content between these markers is replaced on
re-install — put your own instructions outside them.
<!-- END jmix-agent-toolkit -->

## Platform Version

This project uses Jmix 3.1.0 with Java 21. When a skill describes Jmix 2 or
Vaadin 24 APIs, verify the equivalent against the project's Jmix 3.1 / Vaadin 25
dependencies and official migration documentation before applying it.

## Feature Specifications

Store all new feature specifications in `.spec/features/`. This shared folder
holds specifications for all application features; do not create a separate
subfolder for each feature. The main-view specification is
`.spec/features/spec-001-mainview.md`.

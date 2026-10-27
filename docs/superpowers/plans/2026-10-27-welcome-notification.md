# Welcome Notification and Full CI/CD Test Implementation Plan

> Execute inline using the executing-plans skill; use test-driven-development for the notification and verification-before-completion before reporting results.

**Goal:** Show the signed-in user a welcome notification and prove that this visible change travels through tests, a full Docker build, Docker Hub and the existing production deployment.

**Architecture:** Add a localized notification to the existing Jmix main-view controller. Use the prepared delivery workflow and restricted server command, with an explicit manual no-cache image build for this acceptance test.

**Tech stack:** Java 21, Jmix 2.8.1 as configured in build.gradle, Vaadin Flow UI, Gradle, Docker Buildx, GitHub Actions.

**Spec:** User request: when the main view is shown, display `Добро пожаловать в рабочее место финансового менеджера {username}!`, then test the CI/CD pipeline with a complete image rebuild.

## Behavior and constraints

- Replace `{username}` with the authenticated login username, not the user's display name.
- Proposed default: show once per MainView instance on its first ReadyEvent, including entry through a child route. Do not repeat on navigation while that shell remains mounted. A fresh page load/new login may show it again.
- Use a standard informational text notification, top-center, visible for five seconds. Treat the username as text, never HTML.
- Store the text as `ru.stnovator.finassist.view.main/welcomeNotification=Добро пожаловать в рабочее место финансового менеджера %s!` in all existing locale bundles (currently only messages_ru.properties).
- No database, menu, role, or XML layout changes are needed.
- Preserve the prepared restricted deployment model: SSH user deploy, root-owned server command, /root/docker/docker-compose.yml, fixed Docker Hub repository and domain.
- The private deployment key has NOT been uploaded to GitHub. Earlier approval review requires explicit permission for that upload, or the user must add PRODUCTION_SSH_KEY themselves. Do not infer permission from this feature request.

## Task 1: Notification behavior and test

**Files:**
- Modify `src/main/java/ru/stnovator/finassist/view/main/MainView.java`.
- Modify `src/main/resources/ru/stnovator/finassist/messages_ru.properties`.
- Create `src/test/java/ru/stnovator/finassist/view/main/MainViewUiTest.java`.

- [ ] Write a failing @UiTest using the existing FlowuiTestAssistConfiguration pattern: navigate into MainView under an authenticated test user, assert the exact rendered Russian notification including username.
- [ ] Cover a second authenticated username, child-route navigation without duplicate notification, and a fresh main-view instance showing a new notification. Assert actual notification components/text rather than only mocked Notifications calls.
- [ ] Inject CurrentAuthentication and Notifications with @Autowired, MessageBundle with @ViewComponent. In an @Subscribe ReadyEvent handler, format the message and show the notification, guarded by an instance-level flag.
- [ ] Verify through IntelliJ inspections and `./gradlew test`. Check login/navigation in a browser if the application is available.

## Task 2: Explicit full image rebuild

**File:** `.github/workflows/delivery.yml` (already prepared locally).

- [ ] Add boolean workflow_dispatch input `no_cache`, default false. Set build-push-action `no-cache` from it and `pull: true` so the acceptance run rebuilds layers and refreshes base image metadata.
- [ ] Keep deployment opt-in, main-only and serialized; keep PRs unable to publish or deploy.
- [ ] Validate with actionlint and inspect the workflow with IntelliJ.

## Task 3: Publish and run CI

- [ ] Commit the prepared CI/CD files and feature on ci/github-actions; open/update a pull request and run the full test/build checks.
- [ ] Confirm DOCKERHUB_TOKEN authentication (already proven by run 40502796144), production branch policy, PRODUCTION_KNOWN_HOSTS, and the restricted server --check operation.
- [ ] Resolve PRODUCTION_SSH_KEY before attempting deployment. Do not print or commit credentials.
- [ ] Integrate the reviewed changes into main, then manually dispatch the main workflow with `no_cache=true` and `deploy=true` once deployment credentials are ready.

## Task 4: End-to-end acceptance

- [ ] Confirm GitHub test and build jobs succeed, the full no-cache build ran, and Docker Hub received the commit tag and digest.
- [ ] Confirm the production job deploys that digest, restarts only finassist, reloads Nginx, and passes local/public HTTPS checks.
- [ ] With an authorized browser login, verify the welcome notification at https://fin-assist.st-novator.ru, including the actual username and absence of duplicates on menu navigation.
- [ ] Report the workflow URL, commit, image digest and observed UI result. If authenticated browser access is unavailable, state that limitation and request the user's visual confirmation rather than claiming UI acceptance.

## Review focus

- MainView is a router layout; ReadyEvent may fire during child navigation, so test the once-per-instance guard.
- Avoid test users being silently replaced by the UI test harness authentication.
- A full build must disable Docker layer cache, not merely rebuild an application layer with cached dependencies.
- A successful login check does not prove Docker Hub write access; publication is part of this run.
- SSH-key upload approval remains outstanding; a successful build alone is not an end-to-end deployment result.

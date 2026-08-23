# Task 3 Report

## Result

Task 3 is implemented in the feature worktree. The four tool beans, `PromptCatalog`, the two structured-output agents, and the test-only `TestChatModel` plus focused agent tests are in place. The diagnosis agent exposes only read-only tool callbacks, and Task 2's `Optional<ApprovalDecision>` state remains unchanged.

Commit: `aac6790` (`feat: add park tools and alert agents`)

## Red Step

### Command

```powershell
./mvnw.cmd -Dtest='*AgentTest' test
```

### Output

```text
[INFO] Scanning for projects...
[INFO]
[INFO] ---------------< com.example:smart-park-alert-workflow >----------------
[INFO] Building smart-park-alert-workflow 0.0.1-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO]
[INFO] --- resources:3.3.1:resources (default-resources) @ smart-park-alert-workflow ---
[INFO] Copying 1 resource from src\main\resources to target\classes
[INFO] Copying 0 resource from src\main\resources to target\classes
[INFO]
[INFO] --- compiler:3.14.1:compile (default-compile) @ smart-park-alert-workflow ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- resources:3.3.1:testResources (default-testResources) @ smart-park-alert-workflow ---
[INFO] skip non existing resourceDirectory C:\Users\Henry\code\springaialibaba\.worktrees\smart-park-alert-workflow\src\test\resources
[INFO]
[INFO] --- compiler:3.14.1:testCompile (default-testCompile) @ smart-park-alert-workflow ---
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 5 source files with javac [debug parameters release 17] to target\test-classes
[INFO] -------------------------------------------------------------
[ERROR] COMPILATION ERROR :
[INFO] -------------------------------------------------------------
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[10,34] package com.example.smartpark.tool does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[11,34] package com.example.smartpark.tool does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[12,34] package com.example.smartpark.tool does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[13,34] package com.example.smartpark.tool does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[40,9] cannot find symbol
  symbol:   class AlertDiagnosisAgent
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[40,41] cannot find symbol
  symbol:   class AlertDiagnosisAgent
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[42,21] cannot find symbol
  symbol:   class DeviceQueryTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[43,21] cannot find symbol
  symbol:   class AlertQueryTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[44,21] cannot find symbol
  symbol:   class WorkOrderTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[45,21] cannot find symbol
  symbol:   class ParkKnowledgeTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[72,9] cannot find symbol
  symbol:   class AlertDiagnosisAgent
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[72,41] cannot find symbol
  symbol:   class AlertDiagnosisAgent
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[74,21] cannot find symbol
  symbol:   class DeviceQueryTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[75,21] cannot find symbol
  symbol:   class AlertQueryTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[76,21] cannot find symbol
  symbol:   class WorkOrderTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[77,21] cannot find symbol
  symbol:   class ParkKnowledgeTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[88,9] cannot find symbol
  symbol:   class AlertDiagnosisAgent
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[88,41] cannot find symbol
  symbol:   class AlertDiagnosisAgent
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[102,21] cannot find symbol
  symbol:   class DeviceQueryTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[103,21] cannot find symbol
  symbol:   class AlertQueryTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[104,21] cannot find symbol
  symbol:   class WorkOrderTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[105,21] cannot find symbol
  symbol:   class ParkKnowledgeTool
  location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java:[22,25] package AlertTriageAgent does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java:[22,65] cannot find symbol
  symbol:   class AlertTriageAgent
  location: class com.example.smartpark.agent.AlertTriageAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java:[25,65] package AlertTriageAgent does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java:[36,38] cannot find symbol
  symbol:   class AlertTriageAgent
  location: class com.example.smartpark.agent.AlertTriageAgentTest
[INFO] 26 errors
[INFO] -------------------------------------------------------------
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  3.243 s
[INFO] Finished at: 2026-08-23T12:43:30+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:testCompile (default-testCompile) on project smart-park-alert-workflow: Compilation failure: Compilation failure:
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[10,34] package com.example.smartpark.tool does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[11,34] package com.example.smartpark.tool does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[12,34] package com.example.smartpark.tool does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[13,34] package com.example.smartpark.tool does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[40,9] cannot find symbol
[ERROR]   symbol:   class AlertDiagnosisAgent
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[40,41] cannot find symbol
[ERROR]   symbol:   class AlertDiagnosisAgent
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[42,21] cannot find symbol
[ERROR]   symbol:   class DeviceQueryTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[43,21] cannot find symbol
[ERROR]   symbol:   class AlertQueryTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[44,21] cannot find symbol
[ERROR]   symbol:   class WorkOrderTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[45,21] cannot find symbol
[ERROR]   symbol:   class ParkKnowledgeTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[72,9] cannot find symbol
[ERROR]   symbol:   class AlertDiagnosisAgent
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[72,41] cannot find symbol
[ERROR]   symbol:   class AlertDiagnosisAgent
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[74,21] cannot find symbol
[ERROR]   symbol:   class DeviceQueryTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[75,21] cannot find symbol
[ERROR]   symbol:   class AlertQueryTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[76,21] cannot find symbol
[ERROR]   symbol:   class WorkOrderTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[77,21] cannot find symbol
[ERROR]   symbol:   class ParkKnowledgeTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[88,9] cannot find symbol
[ERROR]   symbol:   class AlertDiagnosisAgent
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[88,41] cannot find symbol
[ERROR]   symbol:   class AlertDiagnosisAgent
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[102,21] cannot find symbol
[ERROR]   symbol:   class DeviceQueryTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[103,21] cannot find symbol
[ERROR]   symbol:   class AlertQueryTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[104,21] cannot find symbol
[ERROR]   symbol:   class WorkOrderTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java:[105,21] cannot find symbol
[ERROR]   symbol:   class ParkKnowledgeTool
[ERROR]   location: class com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java:[22,25] package AlertTriageAgent does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java:[22,65] cannot find symbol
[ERROR]   symbol:   class AlertTriageAgent
[ERROR]   location: class com.example.smartpark.agent.AlertTriageAgentTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java:[25,65] package AlertTriageAgent does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java:[36,38] cannot find symbol
[ERROR]   symbol:   class AlertTriageAgent
[ERROR]   location: class com.example.smartpark.agent.AlertTriageAgentTest
[ERROR] -> [Help 1]
[ERROR]
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR]
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

## Green Step

### Command

```powershell
./mvnw.cmd -Dtest='*AgentTest' test
```

### Output

```text
[INFO] Scanning for projects...
[INFO]
[INFO] ---------------< com.example:smart-park-alert-workflow >----------------
[INFO] Building smart-park-alert-workflow 0.0.1-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO]
[INFO] --- resources:3.3.1:resources (default-resources) @ smart-park-alert-workflow ---
[INFO] Copying 1 resource from src\main\resources to target\classes
[INFO] Copying 0 resource from src\main\resources to target\classes
[INFO]
[INFO] --- compiler:3.14.1:compile (default-compile) @ smart-park-alert-workflow ---
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 23 source files with javac [debug parameters release 17] to target\classes
[INFO]
[INFO] --- resources:3.3.1:testResources (default-testResources) @ smart-park-alert-workflow ---
[INFO] skip non existing resourceDirectory C:\Users\Henry\code\springaialibaba\.worktrees\smart-park-alert-workflow\src\test\resources
[INFO]
[INFO] --- compiler:3.14.1:testCompile (default-testCompile) @ smart-park-alert-workflow ---
[INFO] Recompiling the module because of changed dependency.
[INFO] Compiling 5 source files with javac [debug parameters release 17] to target\test-classes
[INFO]
[INFO] --- surefire:3.5.4:test (default-test) @ smart-park-alert-workflow ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO]
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.example.smartpark.agent.AlertDiagnosisAgentTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.458 s -- in com.example.smartpark.agent.AlertDiagnosisAgentTest
[INFO] Running com.example.smartpark.agent.AlertTriageAgentTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in com.example.smartpark.agent.AlertTriageAgentTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  3.847 s
[INFO] Finished at: 2026-08-23T12:46:42+08:00
[INFO] ------------------------------------------------------------------------
```

## Changed Files

- `src/main/java/com/example/smartpark/tool/DeviceQueryTool.java`
- `src/main/java/com/example/smartpark/tool/AlertQueryTool.java`
- `src/main/java/com/example/smartpark/tool/WorkOrderTool.java`
- `src/main/java/com/example/smartpark/tool/ParkKnowledgeTool.java`
- `src/main/java/com/example/smartpark/agent/PromptCatalog.java`
- `src/main/java/com/example/smartpark/agent/AlertTriageAgent.java`
- `src/main/java/com/example/smartpark/agent/AlertDiagnosisAgent.java`
- `src/test/java/com/example/smartpark/agent/TestChatModel.java`
- `src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java`
- `src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java`

## Self-Review

- Each tool bean depends only on its port interface and validates required IDs or queries before calling the port.
- Unknown device or alert IDs are converted into explicit tool result errors instead of invented data.
- `WorkOrderTool.createWorkOrder` delegates directly to `WorkOrderPort.create`, preserving the workflow ID idempotency behavior from Task 2.
- The diagnosis agent exposes only read-only tool callbacks, excluding `createWorkOrder` from its available tool list.
- Both agents use fixed `ChatModel` injection and strict JSON shape validation, with malformed output failing closed via `IllegalStateException`.
- The diagnosis prompt explicitly carries park context, knowledge content, and the evidence-insufficiency rule when no knowledge matches.
- No controller, Graph, workflow, network, or approval-state changes were introduced.

## Concerns

- The current Task 3 scope establishes the tool beans and structured-output boundary, but it does not yet wire a runtime tool-calling loop; that will need the later workflow/Graph layer that the brief explicitly excluded here.

## Round 1 Fix

### Reviewer Findings Addressed

- Wired `AlertDiagnosisAgent` through Spring AI 1.1.2 `ChatClient` so read-only tool callbacks are attached to the actual model request path.
- Changed every public tool method to return structured error results for blank/invalid input instead of throwing.
- Normalized invalid enum/range output to fail closed with `IllegalStateException`.

### Red Step

#### Command

```powershell
./mvnw.cmd -Dtest='*AgentTest' test
```

#### Output

```text
[INFO] Running com.example.smartpark.agent.AlertDiagnosisAgentTest
[ERROR] Tests run: 6, Failures: 2, Errors: 0, Skipped: 0
[ERROR] com.example.smartpark.agent.AlertDiagnosisAgentTest.invalidDiagnosisRiskLevelFailsClosed
Expecting actual throwable to be an instance of:
  java.lang.IllegalStateException
but was:
  java.lang.IllegalArgumentException: No enum constant com.example.smartpark.model.RiskLevel.SEVERE

[ERROR] com.example.smartpark.agent.AlertDiagnosisAgentTest.diagnosisSuppliesReadOnlyToolCallbacksToTheModelRequest
Expecting actual not to be null

[INFO] Running com.example.smartpark.agent.AlertTriageAgentTest
[ERROR] Tests run: 6, Failures: 4, Errors: 0, Skipped: 0
[ERROR] com.example.smartpark.agent.AlertTriageAgentTest.outOfRangeConfidenceFailsClosed
Expecting actual throwable to be an instance of:
  java.lang.IllegalStateException
but was:
  java.lang.IllegalArgumentException: confidence must be between 0 and 1

[ERROR] com.example.smartpark.agent.AlertTriageAgentTest.invalidTriageEnumsFailClosed(String)[1]
but was:
  java.lang.IllegalArgumentException: No enum constant com.example.smartpark.model.AlertClassification.BOGUS

[ERROR] com.example.smartpark.agent.AlertTriageAgentTest.invalidTriageEnumsFailClosed(String)[2]
but was:
  java.lang.IllegalArgumentException: No enum constant com.example.smartpark.agent.AlertTriageAgent.AlertPriority.URGENT

[ERROR] com.example.smartpark.agent.AlertTriageAgentTest.invalidTriageEnumsFailClosed(String)[3]
but was:
  java.lang.IllegalArgumentException: No enum constant com.example.smartpark.model.RiskLevel.SEVERE

[INFO] Tests run: 12, Failures: 6, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

#### Command

```powershell
./mvnw.cmd -Dtest='ParkToolsTest' test
```

#### Output

```text
[INFO] Running com.example.smartpark.tool.ParkToolsTest
[ERROR] Tests run: 7, Failures: 6, Errors: 0, Skipped: 0
[ERROR] deviceLookupReturnsStructuredErrorForBlankDeviceId
Expecting code not to raise a throwable but caught
  "java.lang.IllegalArgumentException: deviceId must not be blank"

[ERROR] alertLookupReturnsStructuredErrorForBlankAlertId
Expecting code not to raise a throwable but caught
  "java.lang.IllegalArgumentException: alertId must not be blank"

[ERROR] alertHistoryReturnsStructuredErrorForBlankDeviceId
Expecting code not to raise a throwable but caught
  "java.lang.IllegalArgumentException: deviceId must not be blank"

[ERROR] workOrderLookupReturnsStructuredErrorForBlankWorkflowId
Expecting code not to raise a throwable but caught
  "java.lang.IllegalArgumentException: workflowId must not be blank"

[ERROR] createWorkOrderReturnsStructuredErrorForBlankSummary
Expecting code not to raise a throwable but caught
  "java.lang.IllegalArgumentException: summary must not be blank"

[ERROR] knowledgeSearchReturnsStructuredErrorForBlankQuery
Expecting code not to raise a throwable but caught
  "java.lang.IllegalArgumentException: query must not be blank"

[INFO] Tests run: 7, Failures: 6, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

### Green Step

#### Command

```powershell
./mvnw.cmd -Dtest='*AgentTest' test
```

#### Output

```text
[INFO] Running com.example.smartpark.agent.AlertDiagnosisAgentTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.486 s
[INFO] Running com.example.smartpark.agent.AlertTriageAgentTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.043 s
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

#### Command

```powershell
./mvnw.cmd -Dtest='ParkToolsTest' test
```

#### Output

```text
[INFO] Running com.example.smartpark.tool.ParkToolsTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.102 s
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Additional Changed Files

- `src/test/java/com/example/smartpark/tool/ParkToolsTest.java`

### Fix Self-Review

- `AlertDiagnosisAgent` now uses `ChatClient.prompt(prompt).toolCallbacks(toolCallbacks).call().chatResponse()`, which attaches the real Spring AI `ToolCallingChatOptions` onto the model request path and keeps `createWorkOrder` excluded.
- Tool beans now return explicit structured errors for blank identifiers, blank summaries, blank queries, and unknown IDs, without leaking `IllegalArgumentException` to callers.
- Triage and diagnosis enum/range failures now consistently surface as `IllegalStateException`, so malformed model output fails closed rather than escaping as mixed exception types.

### Fix Concerns

- The diagnosis agent now passes real callbacks into the request, but the current tests still use a single fixed response path; later workflow steps will need end-to-end tool-call execution coverage once that orchestration layer exists.

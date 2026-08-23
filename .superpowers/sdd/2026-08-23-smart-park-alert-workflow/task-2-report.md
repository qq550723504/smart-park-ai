# Task 2 Report

## Result

Task 2 is implemented in the feature worktree. The domain records and enums, four park ports, deterministic `MockParkSystem`, and `MockParkSystemTest` are in place, and the focused test passes on Java 17 with the Maven Wrapper.

Commit: `df9654c` (`feat: add smart park domain and mock ports`)

## Round 1 Fix

### Red-step command

```powershell
./mvnw.cmd -Dtest=MockParkSystemTest test
```

### Compilation failure output

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
[INFO] Compiling 2 source files with javac [debug parameters release 17] to target\test-classes
[INFO] -------------------------------------------------------------
[ERROR] COMPILATION ERROR :
[INFO] -------------------------------------------------------------
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[3,35] package com.example.smartpark.model does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[4,35] package com.example.smartpark.model does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[5,35] package com.example.smartpark.model does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[13,13] cannot find symbol
  symbol:   class MockParkSystem
  location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[17,30] cannot find symbol
  symbol:   class MockParkSystem
  location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[23,9] cannot find symbol
  symbol:   class Alert
  location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[25,48] cannot find symbol
  symbol:   variable RiskLevel
  location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[31,9] cannot find symbol
  symbol:   class Alert
  location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[33,48] cannot find symbol
  symbol:   variable RiskLevel
  location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[39,9] cannot find symbol
  symbol:   class WorkOrder
  location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[40,9] cannot find symbol
  symbol:   class WorkOrder
  location: class com.example.smartpark.park.mock.MockParkSystemTest
[INFO] 11 errors
[INFO] -------------------------------------------------------------
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  2.934 s
[INFO] Finished at: 2026-08-23T12:24:07+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:testCompile (default-testCompile) on project smart-park-alert-workflow: Compilation failure: Compilation failure:
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[3,35] package com.example.smartpark.model does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[4,35] package com.example.smartpark.model does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[5,35] package com.example.smartpark.model does not exist
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[13,13] cannot find symbol
[ERROR]   symbol:   class MockParkSystem
[ERROR]   location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[17,30] cannot find symbol
[ERROR]   symbol:   class MockParkSystem
[ERROR]   location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[23,9] cannot find symbol
[ERROR]   symbol:   class Alert
[ERROR]   location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[25,48] cannot find symbol
[ERROR]   symbol:   variable RiskLevel
[ERROR]   location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[31,9] cannot find symbol
[ERROR]   symbol:   class Alert
[ERROR]   location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[33,48] cannot find symbol
[ERROR]   symbol:   variable RiskLevel
[ERROR]   location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[39,9] cannot find symbol
[ERROR]   symbol:   class WorkOrder
[ERROR]   location: class com.example.smartpark.park.mock.MockParkSystemTest
[ERROR] /C:/Users/Henry/code/springaialibaba/.worktrees/smart-park-alert-workflow/src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java:[40,9] cannot find symbol
[ERROR]   symbol:   class WorkOrder
[ERROR]   location: class com.example.smartpark.park.mock.MockParkSystemTest
```

## Changed Files

- `src/main/java/com/example/smartpark/model/Alert.java`
- `src/main/java/com/example/smartpark/model/AlertClassification.java`
- `src/main/java/com/example/smartpark/model/Device.java`
- `src/main/java/com/example/smartpark/model/Diagnosis.java`
- `src/main/java/com/example/smartpark/model/KnowledgeDocument.java`
- `src/main/java/com/example/smartpark/model/ParkContext.java`
- `src/main/java/com/example/smartpark/model/WorkOrder.java`
- `src/main/java/com/example/smartpark/model/ApprovalDecision.java`
- `src/main/java/com/example/smartpark/model/RiskLevel.java`
- `src/main/java/com/example/smartpark/model/WorkflowStatus.java`
- `src/main/java/com/example/smartpark/park/DevicePort.java`
- `src/main/java/com/example/smartpark/park/AlertPort.java`
- `src/main/java/com/example/smartpark/park/WorkOrderPort.java`
- `src/main/java/com/example/smartpark/park/KnowledgePort.java`
- `src/main/java/com/example/smartpark/park/mock/MockParkSystem.java`
- `src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java`

## TDD Notes

### Red step

Command:

```powershell
./mvnw.cmd -Dtest=MockParkSystemTest test
```

Result:

- Compilation failed because the new model and mock classes did not exist yet.
- This was the expected RED state before implementation.

### Green step

Command:

```powershell
./mvnw.cmd -Dtest=MockParkSystemTest test
```

Relevant output:

```text
[INFO] Running com.example.smartpark.park.mock.MockParkSystemTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Self-Review

- `MockParkSystem` implements all four requested ports and keeps its state in concurrent in-memory maps.
- Seed data is deterministic and resettable, with `PARK-A`, buildings `A1` and `A2`, HVAC/power/access/pump devices, the two requested alerts, history for both devices, and knowledge documents for overheating, leaks, and power emergencies.
- `create(workflowId, alertId, summary)` is idempotent by `workflowId` and returns the existing work order on repeat calls.
- `ApprovalDecision` uses a closed enum for the decision value, so arbitrary invalid decisions are not accepted.
- No AI calls, controllers, Graph code, or Agent code were added.

## Concerns

- `MockParkSystem` is intentionally narrow and only exposes the contract required by Task 2; later tasks may need additional port methods or richer domain fields.
- The work order defaults to `WAITING_APPROVAL`, which fits the mock workflow boundary here, but later workflow orchestration may decide to transition that state differently.

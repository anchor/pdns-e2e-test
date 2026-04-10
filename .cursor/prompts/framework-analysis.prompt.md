# <TBA> Framework Analysis Playbook
**File:** `framework-analysis.prompt.md`  
**Purpose:** Mapping SUT, Test FW, and Requirements to generate the test bed for testing.
**Note:** Not all prompts added as initially it was not decided to construct a playbook
**Approach** QA engineer defined the initial architecture and used cursor to enrich/update and validate the output
---

## Global Rules
* **Mode:** Use **PLAN MODE** for all analysis (Phases 1-6).
* **Compliance:** Adhere to `.mdc` rules in the project root.
* **Verification:** Never proceed to a subsequent phase without explicit user approval.
* **Evidence:** Reference specific file paths and line numbers where applicable.

---

## Analysis Phases

### Phase 1: Structural Analysis
<prompt_1>

Analyse the project using, @.cursor/context/requirements.md @.cursor/context/powerdns-api.md @.cursor/context/wrapper-doc.md as technical source of truth. Ensure that all the future suggestions adhere strict to the rules defined in @.cursor/rules/core.mdc @.cursor/rules/php-bridge.mdc @.cursor/rules/qa-analysis.mdc @.cursor/rules/documentation.mdc and @.cursor/rules/api-testing.mdc . confirm you have indexed the specific library isgnatures from @@c:\Users\sanjeewa.rathnayake\Downloads\APACDEV-PHPLibrarySpec-090426-0609-3056.pdf

Assuming the role of a senior developer perform a gap analysis between bridge.php and java test files again


Validate this README against documentation.mdc rules and highlight any violations or assumptions.

</prompt_1>

### Phase 2: Functional Mapping
<prompt_2>

</prompt_2>

### Phase 3: Technical Deep-Dive
<prompt_3>

</prompt_3>

### Phase 4: Execution Trace
<prompt_4>


</prompt_4>

### Phase 5: Quality Audit
<prompt_5>

</prompt_5>

### Phase 6: Truth Validation
<prompt_6>
**Goal: The "Mirror" Audit**


### Phase 7: Final Documentation
<prompt_7>
**Goal: Generation for Stakeholders**

Generate the final human-readable documentation based on the validated analysis.
**Audience:** New QA Engineers, Developers, and Non-technical Stakeholders.

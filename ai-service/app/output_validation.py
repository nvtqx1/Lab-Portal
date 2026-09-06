from __future__ import annotations

import copy
from dataclasses import dataclass, field
import hashlib
import json
import math
from pathlib import Path
import re
from types import MappingProxyType
from typing import Annotated, Any, Literal, Mapping, Sequence

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError

from app.models import AssistantKey
from app.models.contracts import to_camel
from app.profiles import AssistantProfile, ProfileLoader


SchemaId = Annotated[
    str,
    StringConstraints(pattern=r"^[a-z0-9]+(?:[._-][a-z0-9]+)*$", min_length=1, max_length=128),
]
ToolId = Annotated[
    str,
    StringConstraints(pattern=r"^[a-z0-9]+(?:[._-][a-z0-9]+)*$", min_length=1, max_length=128),
]
OutputType = Literal["CHAT_RESPONSE", "TOOL_REQUEST", "STRUCTURED_DRAFT"]
ResourceType = Literal[
    "SYSTEM",
    "AUDIT_LOG",
    "USER_ACCOUNT",
    "SYSTEM_CONFIG",
    "LABORATORY",
    "TIME_SLOT",
    "BOOKING",
    "PROJECT",
    "GROUP",
    "TASK",
    "REPORT",
]
RiskBoundary = Literal["READ_ONLY", "DRAFT_ONLY"]
ArgumentName = Literal["resource", "parentResource"]
JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"


class OutputSchemaConfigurationError(RuntimeError):
    def __init__(self, code: str, assistant_key: AssistantKey | None = None) -> None:
        self.code = code
        self.assistant_key = assistant_key
        assistant = f": {assistant_key.value}" if assistant_key is not None else ""
        super().__init__(f"Structured output schema configuration is invalid ({code}{assistant}).")


class UnknownOutputSchemaError(LookupError):
    code = "AI_UNKNOWN_SCHEMA"

    def __init__(self) -> None:
        super().__init__("Unknown structured output schema.")


class UnknownToolSchemaError(LookupError):
    code = "AI_UNKNOWN_TOOL"

    def __init__(self) -> None:
        super().__init__("Unknown tool schema.")


class RegistryModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, extra="forbid", frozen=True, populate_by_name=True)


class OutputSchemaRecord(RegistryModel):
    schema_id: SchemaId
    schema_version: Literal["1.0.0"]
    assistant_key: AssistantKey | None
    output_type: OutputType
    schema_document: dict[str, Any] = Field(alias="schema")


class ToolSchemaRecord(RegistryModel):
    tool_id: ToolId
    assistant_key: AssistantKey
    schema_version: Literal["v1"]
    resource_type: ResourceType
    parent_resource_type: ResourceType | None
    argument_names: tuple[ArgumentName, ...] = Field(min_length=1, max_length=2)
    risk_boundary: RiskBoundary


class OutputSchemaBundle(RegistryModel):
    schema_version: Literal["1.0.0"]
    status: Literal["APPROVED"]
    schemas: tuple[OutputSchemaRecord, ...] = Field(min_length=1)
    tools: tuple[ToolSchemaRecord, ...] = Field(min_length=1)


@dataclass(frozen=True)
class LoadedOutputSchema:
    schema_id: str
    schema_version: str
    assistant_key: AssistantKey | None
    output_type: str
    schema_identity: str
    _validator: Draft202012Validator = field(repr=False, compare=False)

    def is_valid(self, candidate: object) -> bool:
        return self._validator.is_valid(candidate)


class _DuplicateJsonKey(ValueError):
    pass


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateJsonKey
        result[key] = value
    return result


def _canonical_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _identity(value: object) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _has_external_schema_reference(value: object) -> bool:
    if isinstance(value, Mapping):
        for key, item in value.items():
            if key in {"$ref", "$dynamicRef"} and (
                not isinstance(item, str) or not item.startswith("#")
            ):
                return True
            if _has_external_schema_reference(item):
                return True
        return False
    if isinstance(value, list):
        return any(_has_external_schema_reference(item) for item in value)
    return False


def _local_schema_references_resolve(document: Mapping[str, object]) -> bool:
    anchors: set[str] = set()
    references: list[str] = []

    def collect(value: object) -> None:
        if isinstance(value, Mapping):
            for key, item in value.items():
                if key in {"$anchor", "$dynamicAnchor"} and isinstance(item, str):
                    anchors.add(item)
                elif key in {"$ref", "$dynamicRef"} and isinstance(item, str):
                    references.append(item)
                collect(item)
        elif isinstance(value, list):
            for item in value:
                collect(item)

    collect(document)
    for reference in references:
        fragment = reference[1:]
        if not fragment or (not fragment.startswith("/") and fragment in anchors):
            continue
        if not fragment.startswith("/"):
            return False
        current: object = document
        for encoded_token in fragment[1:].split("/"):
            token = encoded_token.replace("~1", "/").replace("~0", "~")
            if isinstance(current, Mapping) and token in current:
                current = current[token]
                continue
            if isinstance(current, list) and token.isdecimal():
                index = int(token)
                if index < len(current):
                    current = current[index]
                    continue
            return False
    return True


_TOOL_SHAPES: Mapping[str, tuple[AssistantKey, str, str | None, str]] = MappingProxyType(
    {
        "admin.system.summary": (AssistantKey.ADMIN_ASSISTANT, "SYSTEM", None, "READ_ONLY"),
        "admin.audit.summary": (AssistantKey.ADMIN_ASSISTANT, "AUDIT_LOG", None, "READ_ONLY"),
        "admin.user.status.lookup": (AssistantKey.ADMIN_ASSISTANT, "USER_ACCOUNT", None, "READ_ONLY"),
        "admin.config.draft": (AssistantKey.ADMIN_ASSISTANT, "SYSTEM_CONFIG", None, "DRAFT_ONLY"),
        "admin.account.action.draft": (AssistantKey.ADMIN_ASSISTANT, "USER_ACCOUNT", None, "DRAFT_ONLY"),
        "lab.policy.read": (AssistantKey.LAB_ASSISTANT, "LABORATORY", None, "READ_ONLY"),
        "lab.slot.read": (AssistantKey.LAB_ASSISTANT, "TIME_SLOT", None, "READ_ONLY"),
        "lab.available.slots.read": (AssistantKey.LAB_ASSISTANT, "LABORATORY", None, "READ_ONLY"),
        "lab.own.booking.read": (AssistantKey.LAB_ASSISTANT, "BOOKING", None, "READ_ONLY"),
        "lab.managed.summary": (AssistantKey.LAB_ASSISTANT, "LABORATORY", None, "READ_ONLY"),
        "lab.shift.create.draft": (AssistantKey.LAB_ASSISTANT, "LABORATORY", None, "DRAFT_ONLY"),
        "lab.booking.draft": (AssistantKey.LAB_ASSISTANT, "TIME_SLOT", None, "DRAFT_ONLY"),
        "lab.checkin.guidance": (AssistantKey.LAB_ASSISTANT, "BOOKING", None, "READ_ONLY"),
        "research.project.summary": (AssistantKey.RESEARCH_ASSISTANT, "PROJECT", None, "READ_ONLY"),
        "research.group.summary": (AssistantKey.RESEARCH_ASSISTANT, "GROUP", None, "READ_ONLY"),
        "research.assigned.task.read": (AssistantKey.RESEARCH_ASSISTANT, "TASK", None, "READ_ONLY"),
        "research.task.proposal.draft": (
            AssistantKey.RESEARCH_ASSISTANT,
            "GROUP",
            "PROJECT",
            "DRAFT_ONLY",
        ),
        "research.task.suggestion.draft": (AssistantKey.RESEARCH_ASSISTANT, "TASK", None, "DRAFT_ONLY"),
        "research.report.review.draft": (AssistantKey.RESEARCH_ASSISTANT, "REPORT", None, "DRAFT_ONLY"),
    }
)
_COMMON_SCHEMA_BINDINGS: Mapping[str, str] = MappingProxyType(
    {
        "chat-response-v1": "CHAT_RESPONSE",
        "tool-request-v1": "TOOL_REQUEST",
    }
)


class OutputSchemaRegistry:
    def __init__(
        self,
        bundle: OutputSchemaBundle,
        schemas: Mapping[str, LoadedOutputSchema],
        tools: Mapping[str, ToolSchemaRecord],
        registry_identity: str,
    ) -> None:
        self._bundle = bundle
        self._schemas = MappingProxyType(dict(schemas))
        self._tools = MappingProxyType(dict(tools))
        self._registry_identity = registry_identity

    @classmethod
    def from_file(cls, path: str | Path, profile_loader: ProfileLoader) -> "OutputSchemaRegistry":
        try:
            raw = Path(path).read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            raise OutputSchemaConfigurationError("OUTPUT_SCHEMA_CONFIG_UNREADABLE") from None
        try:
            data = json.loads(raw, object_pairs_hook=_unique_object)
        except _DuplicateJsonKey:
            raise OutputSchemaConfigurationError("OUTPUT_SCHEMA_CONFIG_DUPLICATE_KEY") from None
        except (json.JSONDecodeError, TypeError):
            raise OutputSchemaConfigurationError("OUTPUT_SCHEMA_CONFIG_INVALID_JSON") from None
        try:
            bundle = OutputSchemaBundle.model_validate(data)
        except ValidationError:
            raise OutputSchemaConfigurationError("OUTPUT_SCHEMA_CONFIG_INVALID") from None

        schema_ids = [record.schema_id for record in bundle.schemas]
        if len(schema_ids) != len(set(schema_ids)):
            raise OutputSchemaConfigurationError("OUTPUT_SCHEMA_ID_DUPLICATE")
        tool_ids = [record.tool_id for record in bundle.tools]
        if len(tool_ids) != len(set(tool_ids)):
            raise OutputSchemaConfigurationError("OUTPUT_TOOL_ID_DUPLICATE")

        schemas: dict[str, LoadedOutputSchema] = {}
        document_ids: set[str] = set()
        for record in bundle.schemas:
            document = record.schema_document
            document_id = document.get("$id")
            if document.get("$schema") != JSON_SCHEMA_DIALECT or not isinstance(document_id, str) or not document_id:
                raise OutputSchemaConfigurationError("OUTPUT_JSON_SCHEMA_INVALID")
            if _has_external_schema_reference(document) or not _local_schema_references_resolve(document):
                raise OutputSchemaConfigurationError("OUTPUT_JSON_SCHEMA_INVALID")
            if document_id in document_ids:
                raise OutputSchemaConfigurationError("OUTPUT_JSON_SCHEMA_ID_DUPLICATE")
            document_ids.add(document_id)
            try:
                Draft202012Validator.check_schema(document)
                validator = Draft202012Validator(document)
            except SchemaError:
                raise OutputSchemaConfigurationError("OUTPUT_JSON_SCHEMA_INVALID") from None
            projection = record.model_dump(by_alias=True, mode="json")
            schemas[record.schema_id] = LoadedOutputSchema(
                schema_id=record.schema_id,
                schema_version=record.schema_version,
                assistant_key=record.assistant_key,
                output_type=record.output_type,
                schema_identity=_identity(projection),
                _validator=validator,
            )

        tools = {record.tool_id: record for record in bundle.tools}
        cls._validate_common_schemas(schemas)
        cls._validate_profiles(profile_loader, schemas, tools)
        cls._validate_tool_catalog(tools)
        projection = {
            "schemaVersion": bundle.schema_version,
            "status": bundle.status,
            "schemas": sorted(
                (record.model_dump(by_alias=True, mode="json") for record in bundle.schemas),
                key=lambda record: record["schemaId"],
            ),
            "tools": sorted(
                (record.model_dump(by_alias=True, mode="json") for record in bundle.tools),
                key=lambda record: record["toolId"],
            ),
        }
        return cls(bundle, schemas, tools, _identity(projection))

    @staticmethod
    def _validate_common_schemas(schemas: Mapping[str, LoadedOutputSchema]) -> None:
        for schema_id, output_type in _COMMON_SCHEMA_BINDINGS.items():
            schema = schemas.get(schema_id)
            if (
                schema is None
                or schema.output_type != output_type
                or schema.assistant_key is not None
            ):
                raise OutputSchemaConfigurationError("OUTPUT_SCHEMA_BINDING_INVALID")

    @staticmethod
    def _validate_tool_catalog(tools: Mapping[str, ToolSchemaRecord]) -> None:
        if set(tools) != set(_TOOL_SHAPES):
            raise OutputSchemaConfigurationError("OUTPUT_TOOL_CATALOG_INVALID")
        for tool_id, tool in tools.items():
            expected_assistant, expected_resource, expected_parent, expected_risk = _TOOL_SHAPES[tool_id]
            expected_arguments = ("resource",) if expected_parent is None else ("resource", "parentResource")
            if (
                tool.assistant_key is not expected_assistant
                or tool.resource_type != expected_resource
                or tool.parent_resource_type != expected_parent
                or tool.risk_boundary != expected_risk
                or tool.argument_names != expected_arguments
            ):
                raise OutputSchemaConfigurationError("OUTPUT_TOOL_CATALOG_INVALID")

    @staticmethod
    def _validate_profiles(
        profile_loader: ProfileLoader,
        schemas: Mapping[str, LoadedOutputSchema],
        tools: Mapping[str, ToolSchemaRecord],
    ) -> None:
        for assistant_key, profile in profile_loader.profiles.items():
            schema = schemas.get(profile.schema_bundle)
            if schema is None:
                raise OutputSchemaConfigurationError("PROFILE_OUTPUT_SCHEMA_UNKNOWN", assistant_key)
            if schema.assistant_key is not assistant_key or schema.output_type != "STRUCTURED_DRAFT":
                raise OutputSchemaConfigurationError("PROFILE_OUTPUT_SCHEMA_MISMATCH", assistant_key)
            for tool_id in profile.allowed_tool_schemas:
                tool = tools.get(tool_id)
                if tool is None:
                    raise OutputSchemaConfigurationError("PROFILE_TOOL_SCHEMA_UNKNOWN", assistant_key)
                if tool.assistant_key is not assistant_key:
                    raise OutputSchemaConfigurationError("PROFILE_TOOL_SCHEMA_MISMATCH", assistant_key)

    @property
    def schema_version(self) -> str:
        return self._bundle.schema_version

    @property
    def registry_identity(self) -> str:
        return self._registry_identity

    @property
    def schemas(self) -> Mapping[str, LoadedOutputSchema]:
        return self._schemas

    @property
    def tools(self) -> Mapping[str, ToolSchemaRecord]:
        return self._tools

    def get_schema(self, schema_id: str) -> LoadedOutputSchema:
        try:
            return self._schemas[schema_id]
        except (KeyError, TypeError):
            raise UnknownOutputSchemaError from None

    def get_tool(self, tool_id: str) -> ToolSchemaRecord:
        try:
            return self._tools[tool_id]
        except (KeyError, TypeError):
            raise UnknownToolSchemaError from None

    def schema_for(self, profile: AssistantProfile, output_type: OutputType) -> LoadedOutputSchema:
        schema_id = {
            "CHAT_RESPONSE": "chat-response-v1",
            "TOOL_REQUEST": "tool-request-v1",
            "STRUCTURED_DRAFT": profile.schema_bundle,
        }.get(output_type)
        if schema_id is None:
            raise UnknownOutputSchemaError
        schema = self.get_schema(schema_id)
        if schema.output_type != output_type or (
            schema.assistant_key is not None and schema.assistant_key is not profile.assistant_key
        ):
            raise UnknownOutputSchemaError
        return schema


class OutputValidationResult(RegistryModel):
    validation_status: Literal["VALID", "INVALID"]
    execution_eligibility: Literal["NOT_EXECUTABLE", "REQUIRES_SPRING_AUTHORIZATION"]
    executable: Literal[False] = False
    diagnostics: tuple[str, ...]
    schema_id: str | None
    schema_identity: str | None


class _InvalidCandidateJson(ValueError):
    pass


_MAX_CANDIDATE_BYTES = 1024 * 1024
_GLOBAL_RESOURCE_TYPES = frozenset({"SYSTEM", "AUDIT_LOG", "SYSTEM_CONFIG"})
_RESOURCE_TYPES = frozenset(
    {
        "SYSTEM",
        "AUDIT_LOG",
        "USER_ACCOUNT",
        "SYSTEM_CONFIG",
        "LABORATORY",
        "TIME_SLOT",
        "BOOKING",
        "PROJECT",
        "GROUP",
        "TASK",
        "REPORT",
    }
)
_RESOURCE_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


def _reject_json_constant(_value: str) -> None:
    raise _InvalidCandidateJson


def _is_json_value(value: object) -> bool:
    if value is None or isinstance(value, (str, bool, int)):
        return True
    if isinstance(value, float):
        return math.isfinite(value)
    if isinstance(value, list):
        return all(_is_json_value(item) for item in value)
    if isinstance(value, Mapping):
        return all(isinstance(key, str) and _is_json_value(item) for key, item in value.items())
    return False


def _parse_candidate(candidate: str | bytes | Mapping[str, object]) -> object:
    if isinstance(candidate, Mapping):
        if not _is_json_value(candidate):
            raise _InvalidCandidateJson
        return copy.deepcopy(dict(candidate))
    if isinstance(candidate, bytes):
        if len(candidate) > _MAX_CANDIDATE_BYTES:
            raise _InvalidCandidateJson
        try:
            candidate = candidate.decode("utf-8", errors="strict")
        except UnicodeDecodeError:
            raise _InvalidCandidateJson from None
    if not isinstance(candidate, str):
        raise _InvalidCandidateJson
    try:
        candidate_size = len(candidate.encode("utf-8", errors="strict"))
    except UnicodeEncodeError:
        raise _InvalidCandidateJson from None
    if candidate_size > _MAX_CANDIDATE_BYTES:
        raise _InvalidCandidateJson
    try:
        return json.loads(
            candidate,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_json_constant,
        )
    except (_DuplicateJsonKey, _InvalidCandidateJson, json.JSONDecodeError, TypeError, UnicodeError):
        raise _InvalidCandidateJson from None


def _reference_key(reference: object) -> tuple[str, str, object] | None:
    if not isinstance(reference, Mapping) or set(reference) != {"resourceType", "resourceId"}:
        return None
    resource_type = reference.get("resourceType")
    resource_id = reference.get("resourceId")
    if not isinstance(resource_type, str) or resource_type not in _RESOURCE_TYPES:
        return None
    if resource_type in _GLOBAL_RESOURCE_TYPES:
        return (resource_type, "null", None) if resource_id is None else None
    if isinstance(resource_id, bool) or resource_id is None:
        return None
    if isinstance(resource_id, int):
        return (resource_type, "integer", resource_id) if resource_id > 0 else None
    if isinstance(resource_id, str) and _RESOURCE_ID_PATTERN.fullmatch(resource_id):
        return resource_type, "string", resource_id
    return None


def _reference_set(references: object) -> frozenset[tuple[str, str, object]] | None:
    if not isinstance(references, (list, tuple)) or len(references) > 64:
        return None
    normalized: list[tuple[str, str, object]] = []
    for reference in references:
        key = _reference_key(reference)
        if key is None:
            return None
        normalized.append(key)
    if len(normalized) != len(set(normalized)):
        return None
    return frozenset(normalized)


class StructuredOutputValidator:
    """Validates untrusted model output without granting execution authority."""

    def __init__(self, registry: OutputSchemaRegistry) -> None:
        self._registry = registry

    def validate(
        self,
        profile: AssistantProfile,
        expected_output_type: str,
        candidate_output: str | bytes | Mapping[str, object],
        authorized_resource_references: Sequence[Mapping[str, object]] | None = None,
    ) -> OutputValidationResult:
        try:
            candidate = _parse_candidate(candidate_output)
        except _InvalidCandidateJson:
            return self._invalid("AI_OUTPUT_INVALID_JSON")
        try:
            schema = self._registry.schema_for(profile, expected_output_type)  # type: ignore[arg-type]
        except UnknownOutputSchemaError:
            return self._invalid("AI_UNKNOWN_SCHEMA")
        if not isinstance(candidate, dict):
            return self._invalid("AI_OUTPUT_SCHEMA_INVALID", schema)

        authorized = _reference_set(list(authorized_resource_references or ()))
        if authorized is None:
            return self._invalid("AI_INVALID_RESOURCE_REFERENCE", schema)

        if expected_output_type == "TOOL_REQUEST":
            diagnostic = self._validate_tool(profile, candidate, authorized)
            if diagnostic is not None:
                return self._invalid(diagnostic, schema)
        if not schema.is_valid(candidate):
            return self._invalid("AI_OUTPUT_SCHEMA_INVALID", schema)
        if expected_output_type in {"CHAT_RESPONSE", "TOOL_REQUEST"} and candidate.get(
            "assistantKey"
        ) != profile.assistant_key.value:
            return self._invalid("AI_OUTPUT_SCHEMA_INVALID", schema)

        candidate_references = self._candidate_references(expected_output_type, candidate)
        if candidate_references is None or not candidate_references.issubset(authorized):
            return self._invalid("AI_INVALID_RESOURCE_REFERENCE", schema)
        eligibility = (
            "REQUIRES_SPRING_AUTHORIZATION" if expected_output_type == "TOOL_REQUEST" else "NOT_EXECUTABLE"
        )
        return OutputValidationResult(
            validation_status="VALID",
            execution_eligibility=eligibility,
            executable=False,
            diagnostics=(),
            schema_id=schema.schema_id,
            schema_identity=schema.schema_identity,
        )

    def _validate_tool(
        self,
        profile: AssistantProfile,
        candidate: Mapping[str, object],
        authorized: frozenset[tuple[str, str, object]],
    ) -> str | None:
        if candidate.get("assistantKey") != profile.assistant_key.value:
            return "AI_OUTPUT_SCHEMA_INVALID"
        tool_id = candidate.get("toolId")
        if not isinstance(tool_id, str) or not tool_id:
            return "AI_UNKNOWN_TOOL"
        try:
            tool = self._registry.get_tool(tool_id)
        except UnknownToolSchemaError:
            return "AI_UNKNOWN_TOOL"
        if tool_id not in profile.allowed_tool_schemas or tool.assistant_key is not profile.assistant_key:
            return "AI_TOOL_NOT_ALLOWED"
        if candidate.get("schemaVersion") != tool.schema_version:
            return "AI_INVALID_TOOL_ARGUMENTS"
        arguments = candidate.get("arguments")
        expected_arguments = set(tool.argument_names)
        if not isinstance(arguments, Mapping) or set(arguments) != expected_arguments:
            return "AI_INVALID_TOOL_ARGUMENTS"

        resource = _reference_key(arguments.get("resource"))
        if resource is None or resource[0] != tool.resource_type or resource not in authorized:
            return "AI_INVALID_RESOURCE_REFERENCE"
        if tool.parent_resource_type is not None:
            parent = _reference_key(arguments.get("parentResource"))
            if parent is None or parent[0] != tool.parent_resource_type or parent not in authorized:
                return "AI_INVALID_RESOURCE_REFERENCE"
        return None

    def _candidate_references(
        self,
        expected_output_type: str,
        candidate: Mapping[str, object],
    ) -> frozenset[tuple[str, str, object]] | None:
        if expected_output_type == "TOOL_REQUEST":
            arguments = candidate.get("arguments")
            if not isinstance(arguments, Mapping):
                return None
            return _reference_set(list(arguments.values()))
        if expected_output_type == "CHAT_RESPONSE":
            metadata = candidate.get("metadata")
            if not isinstance(metadata, Mapping):
                return None
            references = metadata.get("resourceReferences", [])
            return _reference_set(references)
        kind = candidate.get("kind")
        draft_references: dict[str, list[dict[str, object]]] = {
            "ADMIN_ACCOUNT_DRAFT": [],
            "LAB_BOOKING_DRAFT": [
                {"resourceType": "LABORATORY", "resourceId": candidate.get("labRef")},
                {"resourceType": "TIME_SLOT", "resourceId": candidate.get("slotRef")},
            ],
            "LAB_SHIFT_CREATE_DRAFT": [
                {"resourceType": "LABORATORY", "resourceId": candidate.get("labRef")},
            ],
            "LAB_SHIFT_CREATE_CLARIFICATION": [
                {"resourceType": "LABORATORY", "resourceId": candidate.get("labRef")},
            ],
            "RESEARCH_TASK_PROPOSAL_DRAFT": [
                {"resourceType": "PROJECT", "resourceId": candidate.get("projectRef")},
                {"resourceType": "GROUP", "resourceId": candidate.get("groupRef")},
            ],
            "RESEARCH_TASK_SUGGESTION_DRAFT": [
                {"resourceType": "TASK", "resourceId": candidate.get("taskRef")},
            ],
            "RESEARCH_REPORT_REVIEW_DRAFT": [
                {"resourceType": "REPORT", "resourceId": candidate.get("reportRef")},
            ],
        }
        references = draft_references.get(kind) if isinstance(kind, str) else None
        return _reference_set(references) if references is not None else None

    @staticmethod
    def _invalid(code: str, schema: LoadedOutputSchema | None = None) -> OutputValidationResult:
        return OutputValidationResult(
            validation_status="INVALID",
            execution_eligibility="NOT_EXECUTABLE",
            executable=False,
            diagnostics=(code,),
            schema_id=schema.schema_id if schema is not None else None,
            schema_identity=schema.schema_identity if schema is not None else None,
        )

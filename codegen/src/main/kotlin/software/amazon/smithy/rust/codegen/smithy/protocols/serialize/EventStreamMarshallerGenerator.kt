/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rust.codegen.smithy.protocols.serialize

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.BooleanShape
import software.amazon.smithy.model.shapes.ByteShape
import software.amazon.smithy.model.shapes.IntegerShape
import software.amazon.smithy.model.shapes.LongShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShortShape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.TimestampShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EventHeaderTrait
import software.amazon.smithy.model.traits.EventPayloadTrait
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.RustModule
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.render
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.withBlock
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.smithy.isOptional
import software.amazon.smithy.rust.codegen.smithy.rustType
import software.amazon.smithy.rust.codegen.util.dq
import software.amazon.smithy.rust.codegen.util.hasTrait
import software.amazon.smithy.rust.codegen.util.toPascalCase

class EventStreamMarshallerGenerator(
    private val model: Model,
    runtimeConfig: RuntimeConfig,
    private val symbolProvider: RustSymbolProvider,
    private val unionShape: UnionShape,
    private val serializerGenerator: StructuredDataSerializerGenerator,
    private val payloadContentType: String,
) {
    private val smithyEventStream = CargoDependency.SmithyEventStream(runtimeConfig)
    private val eventStreamSerdeModule = RustModule.private("event_stream_serde")
    private val codegenScope = arrayOf(
        "MarshallMessage" to RuntimeType("MarshallMessage", smithyEventStream, "smithy_eventstream::frame"),
        "Message" to RuntimeType("Message", smithyEventStream, "smithy_eventstream::frame"),
        "Header" to RuntimeType("Header", smithyEventStream, "smithy_eventstream::frame"),
        "HeaderValue" to RuntimeType("HeaderValue", smithyEventStream, "smithy_eventstream::frame"),
        "Error" to RuntimeType("Error", smithyEventStream, "smithy_eventstream::error"),
    )

    fun render(): RuntimeType {
        val marshallerType = unionShape.eventStreamMarshallerType()
        val unionSymbol = symbolProvider.toSymbol(unionShape)

        return RuntimeType.forInlineFun("${marshallerType.name}::new", eventStreamSerdeModule) { inlineWriter ->
            inlineWriter.renderMarshaller(marshallerType, unionSymbol)
        }
    }

    private fun RustWriter.renderMarshaller(marshallerType: RuntimeType, unionSymbol: Symbol) {
        rust(
            """
            ##[non_exhaustive]
            ##[derive(Debug)]
            pub struct ${marshallerType.name};

            impl ${marshallerType.name} {
                pub fn new() -> Self {
                    ${marshallerType.name}
                }
            }
            """
        )

        rustBlockTemplate(
            "impl #{MarshallMessage} for ${marshallerType.name}",
            *codegenScope
        ) {
            rust("type Input = ${unionSymbol.rustType().render(fullyQualified = true)};")

            rustBlockTemplate(
                "fn marshall(&self, input: Self::Input) -> std::result::Result<#{Message}, #{Error}>",
                *codegenScope
            ) {
                rust("let mut headers = Vec::new();")
                addStringHeader(":message-type", "\"event\".into()")
                rustBlock("let payload = match input") {
                    for (member in unionShape.members()) {
                        val eventType = member.memberName // must be the original name, not the Rust-safe name
                        rustBlock("Self::Input::${member.memberName.toPascalCase()}(inner) => ") {
                            addStringHeader(":event-type", "${eventType.dq()}.into()")
                            val target = model.expectShape(member.target, StructureShape::class.java)
                            renderMarshallEvent(member, target)
                        }
                    }
                }
                rustTemplate("; Ok(#{Message}::new_from_parts(headers, payload))", *codegenScope)
            }
        }
    }

    private fun RustWriter.renderMarshallEvent(unionMember: MemberShape, eventStruct: StructureShape) {
        val headerMembers = eventStruct.members().filter { it.hasTrait<EventHeaderTrait>() }
        val payloadMember = eventStruct.members().firstOrNull { it.hasTrait<EventPayloadTrait>() }
        for (member in headerMembers) {
            val memberName = symbolProvider.toMemberName(member)
            val target = model.expectShape(member.target)
            renderMarshallEventHeader(memberName, member, target)
        }
        if (payloadMember != null) {
            val memberName = symbolProvider.toMemberName(payloadMember)
            val target = model.expectShape(payloadMember.target)
            renderMarshallEventPayload("inner.$memberName", payloadMember, target)
        } else if (headerMembers.isEmpty()) {
            renderMarshallEventPayload("inner", unionMember, eventStruct)
        } else {
            rust("Vec::new()")
        }
    }

    private fun RustWriter.renderMarshallEventHeader(memberName: String, member: MemberShape, target: Shape) {
        val headerName = member.memberName
        handleOptional(
            symbolProvider.toSymbol(member).isOptional(),
            "inner.$memberName",
            "value",
            { input -> renderAddHeader(headerName, input, target) },
        )
    }

    private fun RustWriter.renderAddHeader(headerName: String, inputName: String, target: Shape) {
        withBlock("headers.push(", ");") {
            rustTemplate(
                "#{Header}::new(${headerName.dq()}, #{HeaderValue}::${headerValue(inputName, target)})",
                *codegenScope
            )
        }
    }

    // Event stream header types: https://awslabs.github.io/smithy/1.0/spec/core/stream-traits.html#eventheader-trait
    // Note: there are no floating point header types for Event Stream.
    private fun headerValue(inputName: String, target: Shape): String = when (target) {
        is BooleanShape -> "Bool($inputName)"
        is ByteShape -> "Byte($inputName)"
        is ShortShape -> "Int16($inputName)"
        is IntegerShape -> "Int32($inputName)"
        is LongShape -> "Int64($inputName)"
        is BlobShape -> "ByteArray($inputName.into_inner().into())"
        is StringShape -> "String($inputName.into())"
        is TimestampShape -> "Timestamp($inputName)"
        else -> throw IllegalStateException("unsupported event stream header shape type: $target")
    }

    private fun RustWriter.renderMarshallEventPayload(inputExpr: String, member: MemberShape, target: Shape) {
        val optional = symbolProvider.toSymbol(member).isOptional()
        if (target is BlobShape || target is StringShape) {
            data class PayloadContext(val conversionFn: String, val contentType: String)
            val ctx = when (target) {
                is BlobShape -> PayloadContext("into_inner", "application/octet-stream")
                is StringShape -> PayloadContext("into_bytes", "text/plain")
                else -> throw IllegalStateException("unreachable")
            }
            addStringHeader(":content-type", "${ctx.contentType.dq()}.into()")
            handleOptional(
                optional,
                inputExpr,
                "inner_payload",
                { input -> rust("$input.${ctx.conversionFn}()") },
                { rust("Vec::new()") }
            )
        } else {
            addStringHeader(":content-type", "${payloadContentType.dq()}.into()")

            val serializerFn = serializerGenerator.payloadSerializer(member)
            handleOptional(
                optional,
                inputExpr,
                "inner_payload",
                { input ->
                    rustTemplate(
                        """
                        #{serializerFn}(&$input)
                            .map_err(|err| #{Error}::Marshalling(format!("{}", err)))?
                        """,
                        "serializerFn" to serializerFn,
                        *codegenScope
                    )
                },
                { rust("unimplemented!(\"TODO(EventStream): Figure out what to do when there's no payload\")") }
            )
        }
    }

    private fun RustWriter.handleOptional(
        optional: Boolean,
        inputExpr: String,
        someName: String,
        writeSomeCase: RustWriter.(String) -> Unit,
        writeNoneCase: (RustWriter.() -> Unit)? = null,
    ) {
        if (optional) {
            rustBlock("if let Some($someName) = $inputExpr") {
                writeSomeCase(someName)
            }
            if (writeNoneCase != null) {
                rustBlock(" else ") {
                    writeNoneCase()
                }
            }
        } else {
            writeSomeCase(inputExpr)
        }
    }

    private fun RustWriter.addStringHeader(name: String, valueExpr: String) {
        rustTemplate("headers.push(#{Header}::new(${name.dq()}, #{HeaderValue}::String($valueExpr)));", *codegenScope)
    }

    private fun UnionShape.eventStreamMarshallerType(): RuntimeType {
        val symbol = symbolProvider.toSymbol(this)
        return RuntimeType("${symbol.name.toPascalCase()}Marshaller", null, "crate::event_stream_serde")
    }
}
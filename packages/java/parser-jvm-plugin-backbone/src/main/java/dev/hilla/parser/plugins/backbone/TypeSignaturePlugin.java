package dev.hilla.parser.plugins.backbone;

import javax.annotation.Nonnull;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

import dev.hilla.parser.core.AbstractPlugin;
import dev.hilla.parser.core.PluginConfiguration;
import dev.hilla.parser.models.ArraySignatureModel;
import dev.hilla.parser.models.ClassRefSignatureModel;
import dev.hilla.parser.models.SignatureModel;
import dev.hilla.parser.models.SpecializedModel;
import dev.hilla.parser.models.TypeArgumentModel;
import dev.hilla.parser.models.TypeParameterModel;
import dev.hilla.parser.models.TypeVariableModel;
import dev.hilla.parser.node.EntityNode;
import dev.hilla.parser.node.FieldNode;
import dev.hilla.parser.node.MethodNode;
import dev.hilla.parser.node.MethodParameterNode;
import dev.hilla.parser.node.NodeDependencies;
import dev.hilla.parser.node.NodePath;
import dev.hilla.parser.node.TypeSignatureNode;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;

public class TypeSignaturePlugin extends AbstractPlugin<PluginConfiguration> {
    @Nonnull
    @Override
    public NodeDependencies scan(@Nonnull NodeDependencies nodeDependencies) {
        var node = nodeDependencies.getNode();
        if (node instanceof MethodNode) {
            return scanMethodNode((MethodNode) node, nodeDependencies);
        } else if (node instanceof MethodParameterNode) {
            return scanMethodParameter((MethodParameterNode) node);
        } else if (node instanceof EntityNode) {
            return scanEntity((EntityNode) node, nodeDependencies);
        } else if (node instanceof FieldNode) {
            return scanField((FieldNode) node);
        } else if (node instanceof TypeSignatureNode) {
            return scanTypeSignature((TypeSignatureNode) node,
                nodeDependencies);
        }

        return nodeDependencies;
    }

    @Override
    public void enter(NodePath<?> nodePath) {
        if (nodePath.getNode() instanceof TypeSignatureNode) {
            var typeSignatureNode = (TypeSignatureNode) nodePath.getNode();
            typeSignatureNode.setTarget(
                new SchemaProcessor(typeSignatureNode.getSource()).process());
        }
    }

    @Override
    public void exit(NodePath<?> nodePath) {
        if (!(nodePath.getNode() instanceof TypeSignatureNode)) {
            return;
        }

        var node = (TypeSignatureNode) nodePath.getNode();
        var schema = node.getTarget();
        var parentNode = nodePath.getParentPath().getNode();
        var grandParentNode = nodePath.getParentPath().getParentPath()
            .getNode();
        if (parentNode instanceof MethodNode) {
            attachSchemaToMethod(schema, (MethodNode) parentNode);
        } else if (parentNode instanceof MethodParameterNode &&
            grandParentNode instanceof MethodNode) {
            attachSchemaToParameterOfMethod(schema,
                ((MethodParameterNode) parentNode),
                ((MethodNode) grandParentNode));
        } else if (parentNode instanceof EntityNode &&
            schema instanceof ComposedSchema) {
            attachSchemaToEntitySubclass((ComposedSchema) schema,
                (EntityNode) parentNode);
        } else if (parentNode instanceof FieldNode &&
            grandParentNode instanceof EntityNode) {
            attachSchemaToFieldOfEntity(schema, (FieldNode) parentNode,
                (EntityNode) grandParentNode);
        } else if (parentNode instanceof TypeSignatureNode) {
            attachSchemaToNestingParentSignature(schema,
                (TypeSignatureNode) parentNode);
        }
    }

    private void attachSchemaToMethod(Schema<?> schema, MethodNode methodNode) {
        methodNode.getTarget().getPost().getResponses().get("200").setContent(
            new Content().addMediaType(MethodPlugin.MEDIA_TYPE,
                new MediaType().schema(schema)));
    }

    private void attachSchemaToParameterOfMethod(Schema<?> schema,
        MethodParameterNode methodParameterNode, MethodNode methodNode) {
        var requestMap = (ObjectSchema) methodNode.getTarget().getPost()
            .getRequestBody().getContent().get(MethodPlugin.MEDIA_TYPE)
            .getSchema();
        requestMap.addProperties(methodParameterNode.getTarget(), schema);
    }

    private void attachSchemaToEntitySubclass(ComposedSchema schema,
        EntityNode entityNode) {
        var subclassSchema = entityNode.getTarget();
        schema.addAnyOfItem(subclassSchema);
        schema.setNullable(null);
        entityNode.setTarget(schema);
    }

    private void attachSchemaToFieldOfEntity(Schema<?> schema,
        FieldNode fieldNode, EntityNode entityNode) {
        var fieldName = fieldNode.getTarget();
        entityNode.getTarget().addProperties(fieldName, schema);
    }

    private void attachSchemaToNestingParentSignature(Schema<?> schema,
        TypeSignatureNode parentNode) {
        var parentSchema = parentNode.getTarget();
        if (parentSchema instanceof ArraySchema) {
            ((ArraySchema) parentSchema).setItems(schema);
        } else if (parentSchema instanceof MapSchema) {
            parentSchema.additionalProperties(schema);
        } else {
            // The nested schema replaces parent for type argument, type
            // parameter, type variable, and optional signatures
            parentNode.setTarget(schema);
        }
    }

    private NodeDependencies scanMethodNode(MethodNode methodNode,
        NodeDependencies nodeDependencies) {
        if (methodNode.getSource().getResultType().isVoid()) {
            return nodeDependencies;
        }

        return NodeDependencies.of(methodNode,
            Stream.concat(nodeDependencies.getChildNodes(), Stream.of(
                TypeSignatureNode.of(methodNode.getSource().getResultType()))),
            nodeDependencies.getRelatedNodes());
    }

    private NodeDependencies scanMethodParameter(
        MethodParameterNode methodParameterNode) {
        return NodeDependencies.of(methodParameterNode, Stream.of(
                TypeSignatureNode.of(methodParameterNode.getSource().getType())),
            Stream.empty());
    }

    private NodeDependencies scanEntity(EntityNode node,
        NodeDependencies nodeDependencies) {
        var cls = node.getSource();
        if (cls.getSuperClass().isPresent() &&
            cls.getSuperClass().get().isNonJDKClass()) {
            return NodeDependencies.of(node,
                Stream.concat(nodeDependencies.getChildNodes(),
                    Stream.of(TypeSignatureNode.of(cls.getSuperClass().get()))),
                Stream.empty());
        }

        return nodeDependencies;
    }

    private NodeDependencies scanField(FieldNode fieldNode) {
        return NodeDependencies.of(fieldNode,
            Stream.of(TypeSignatureNode.of(fieldNode.getSource().getType())),
            Stream.empty());
    }

    private NodeDependencies scanTypeSignature(TypeSignatureNode node,
        NodeDependencies nodeDependencies) {
        var signature = node.getSource();
        if (signature.isArray()) {
            return scanNestedTypeSignature(nodeDependencies,
                ((ArraySignatureModel) signature).getNestedType());
        } else if (signature.isIterable() &&
            !((ClassRefSignatureModel) signature).getTypeArguments()
                .isEmpty()) {
            return scanNestedTypeSignature(nodeDependencies,
                ((ClassRefSignatureModel) signature).getTypeArguments().get(0));
        } else if (signature.isOptional()) {
            return scanNestedTypeSignature(nodeDependencies,
                ((ClassRefSignatureModel) signature).getTypeArguments().get(0));
        } else if (signature.isMap()) {
            return scanNestedTypeSignature(nodeDependencies,
                ((ClassRefSignatureModel) signature).getTypeArguments().get(1));
        } else if (signature.isTypeArgument() &&
            !((TypeArgumentModel) signature).getAssociatedTypes().isEmpty()) {
            return scanNestedTypeSignature(nodeDependencies,
                ((TypeArgumentModel) signature).getAssociatedTypes().get(0));
        } else if (signature.isTypeParameter()) {
            var typeParameterModel = ((TypeParameterModel) signature);
            return NodeDependencies.of(node,
                typeParameterModel.getBoundsStream().filter(Objects::nonNull)
                    .filter(Predicate.not(SpecializedModel::isNativeObject))
                    .map(TypeSignatureNode::of), Stream.empty());
        } else if (signature.isTypeVariable()) {
            return scanNestedTypeSignature(nodeDependencies,
                ((TypeVariableModel) signature).resolve());
        }

        return nodeDependencies;
    }

    private NodeDependencies scanNestedTypeSignature(
        NodeDependencies nodeDependencies,
        SignatureModel... nestedSignatureModels) {
        return NodeDependencies.of(nodeDependencies.getNode(),
            Stream.of(nestedSignatureModels).map(TypeSignatureNode::of),
            Stream.empty());
    }
}
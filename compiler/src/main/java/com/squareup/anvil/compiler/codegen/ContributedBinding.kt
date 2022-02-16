package com.squareup.anvil.compiler.codegen

import com.squareup.anvil.annotations.ContributesBinding.Priority
import com.squareup.anvil.compiler.api.AnvilCompilationException
import com.squareup.anvil.compiler.injectFqName
import com.squareup.anvil.compiler.internal.argumentType
import com.squareup.anvil.compiler.internal.classDescriptor
import com.squareup.anvil.compiler.internal.isQualifier
import com.squareup.anvil.compiler.internal.reference.AnnotationReference
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.ClassReference.Descriptor
import com.squareup.anvil.compiler.internal.reference.ClassReference.Psi
import com.squareup.anvil.compiler.internal.reference.allSuperTypeClassReferences
import com.squareup.anvil.compiler.internal.reference.asClassName
import com.squareup.anvil.compiler.internal.requireFqName
import com.squareup.anvil.compiler.qualifierFqName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.TypeName
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.jetbrains.kotlin.resolve.constants.KClassValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType

internal data class ContributedBinding(
  val contributedClass: ClassReference,
  val mapKeys: List<AnnotationSpec>,
  val qualifiers: List<AnnotationSpec>,
  val boundTypeClassName: TypeName,
  val priority: Priority,
  val qualifiersKeyLazy: Lazy<String>
)

internal fun AnnotationReference.toContributedBinding(
  isMultibinding: Boolean
): ContributedBinding {
  val boundType = requireBoundType()

  val mapKeys = if (isMultibinding) {
    declaringClass().annotations.filter { it.isMapKey() }.map { it.toAnnotationSpec() }
  } else {
    emptyList()
  }

  val ignoreQualifier = ignoreQualifier()
  val qualifiers = if (ignoreQualifier) {
    emptyList()
  } else {
    declaringClass().annotations.filter { it.isQualifier() }.map { it.toAnnotationSpec() }
  }

  return ContributedBinding(
    contributedClass = declaringClass(),
    mapKeys = mapKeys,
    qualifiers = qualifiers,
    boundTypeClassName = boundType.asClassName(),
    priority = priority(),
    qualifiersKeyLazy = declaringClass()
      .qualifiersKeyLazy(module, boundType.fqName, ignoreQualifier)
  )
}

private fun AnnotationReference.requireBoundType(): ClassReference {
  val boundFromAnnotation = boundTypeOrNull()

  if (boundFromAnnotation != null) {
    // ensure that the bound type is actually a supertype of the contributing class
    val boundType = declaringClass().allSuperTypeClassReferences()
      .firstOrNull { it.fqName == boundFromAnnotation.fqName }
      ?: throw AnvilCompilationException(
        "$fqName contributes a binding for ${boundFromAnnotation.fqName}, " +
          "but doesn't extend this type."
      )

    boundType.checkNotGeneric(contributedClass = declaringClass())
    return boundType
  }

  // If there's no bound type in the annotation,
  // it must be the only supertype of the contributing class
  val boundType = declaringClass().directSuperClassReferences().singleOrNull()
    ?: throw AnvilCompilationException(
      message = "$fqName contributes a binding, but does not " +
        "specify the bound type. This is only allowed with exactly one direct super type. " +
        "If there are multiple or none, then the bound type must be explicitly defined in " +
        "the @$shortName annotation."
    )

  boundType.checkNotGeneric(contributedClass = declaringClass())
  return boundType
}

private fun ClassReference.checkNotGeneric(
  contributedClass: ClassReference
) {
  fun exceptionText(typeString: String): String {
    return "Class ${contributedClass.fqName} binds $fqName," +
      " but the bound type contains type parameter(s) $typeString." +
      " Type parameters in bindings are not supported. This binding needs" +
      " to be contributed in a Dagger module manually."
  }

  fun KotlinType.describeTypeParameters(): String = arguments
    .ifEmpty { return "" }
    .joinToString(prefix = "<", postfix = ">") { typeArgument ->
      typeArgument.type.toString() + typeArgument.type.describeTypeParameters()
    }

  when (this) {
    is Descriptor -> {
      if (clazz.declaredTypeParameters.isNotEmpty()) {

        throw AnvilCompilationException(
          classDescriptor = clazz,
          message = exceptionText(clazz.defaultType.describeTypeParameters())
        )
      }
    }
    is Psi -> {
      if (clazz.typeParameters.isNotEmpty()) {
        val typeString = clazz.typeParameters
          .joinToString(prefix = "<", postfix = ">") { it.name!! }

        throw AnvilCompilationException(
          message = exceptionText(typeString),
          element = clazz.nameIdentifier
        )
      }
    }
  }
}

// TODO: clean this up more. Can we use ClassReference?
private fun ClassReference.qualifiersKeyLazy(
  module: ModuleDescriptor,
  boundTypeFqName: FqName,
  ignoreQualifier: Boolean
): Lazy<String> {

  // Careful! If we ever decide to support generic types, then we might need to use the
  // Kotlin type and not just the FqName.
  if (ignoreQualifier) {
    return lazy { boundTypeFqName.asString() }
  }

  return when (this) {
    is Descriptor -> lazy { clazz.qualifiersKey(module, boundTypeFqName) }
    is Psi -> lazy { clazz.qualifiersKey(module, boundTypeFqName) }
  }
}

private fun KtClassOrObject.qualifiersKey(
  module: ModuleDescriptor,
  boundTypeFqName: FqName
): String {

  // Note that we sort all elements. That's important for a stable string comparison.
  val allArguments = annotationEntries
    // filter out anything which isn't a qualifier
    .mapNotNull { annotationEntry ->
      val descriptor = annotationEntry.typeReference
        ?.requireFqName(module)
        ?.takeIf { it != injectFqName }
        ?.classDescriptor(module)
        ?.takeIf { it.annotations.hasAnnotation(qualifierFqName) }
        ?: return@mapNotNull null
      annotationEntry to descriptor
    }
    .sortedBy { it.second.fqNameSafe.hashCode() }
    .joinToString(separator = "") { (entry, descriptor) ->

      val annotationFqName = descriptor.fqNameSafe
      val annotationFqNameString = annotationFqName.asString()

      val valueParams = descriptor.constructors
        .first()
        .valueParameters

      val argumentString = entry.valueArguments
        .mapIndexed { index, valueArgument ->

          // If arguments don't have names, then the name can be found at that index
          val name = valueArgument.getArgumentName()?.asName?.identifier
            ?: valueParams[index].name.identifier

          fun KtExpression.valueString(): String {
            return when (this) {
              is KtClassLiteralExpression -> requireFqName(module).asString()
              is KtNameReferenceExpression -> getReferencedName()
              // primitives
              else -> text
            }
          }

          val valueString = valueArgument.getArgumentExpression()?.valueString()

          name + valueString
        }

      annotationFqNameString + argumentString
    }

  return boundTypeFqName.asString() + allArguments
}

private fun ClassDescriptor.qualifiersKey(
  module: ModuleDescriptor,
  boundTypeName: FqName
): String {

  // Note that we sort all elements. That's important for a stable string comparison.
  val allArguments = annotations
    .filter { it.isQualifier() }
    .sortedBy { it.requireFqName().hashCode() }
    .joinToString(separator = "") { annotation ->
      val annotationFqName = annotation.requireFqName().asString()

      val argumentString = annotation.allValueArguments
        .toList()
        .sortedBy { it.first }
        .map { (name, value) ->
          val valueString = when (value) {
            is KClassValue -> value.argumentType(module)
              .classDescriptor().fqNameSafe.asString()
            is EnumValue -> value.enumEntryName.asString()
            // String, int, long, ... other primitives.
            else -> value.toString()
          }

          name.asString() + valueString
        }

      annotationFqName + argumentString
    }

  return boundTypeName.asString() + allArguments
}

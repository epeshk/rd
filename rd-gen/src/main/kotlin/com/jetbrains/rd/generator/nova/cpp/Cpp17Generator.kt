package com.jetbrains.rider.generator.nova.cpp

import com.jetbrains.rider.generator.nova.*
import com.jetbrains.rider.generator.nova.Enum
import com.jetbrains.rider.generator.nova.FlowKind.*
import com.jetbrains.rider.generator.nova.util.joinToOptString
import com.jetbrains.rider.util.eol
import com.jetbrains.rider.util.hash.IncrementalHash64
import com.jetbrains.rider.util.string.Eol
import com.jetbrains.rider.util.string.PrettyPrinter
import com.jetbrains.rider.util.string.condstr
import java.io.File

class Signature(private val returnType: String, private val arguments: String, private val scope: String, private var isAbstract: Boolean = false) {
    private var declPrefix = arrayListOf<String>()
    private var declPostfix = arrayListOf<String>()
    private var commonPostfix = arrayListOf<String>()

    protected fun <T> ArrayList<T>.front(): String {
        return toArray().joinToOptString(separator = " ", postfix = " ")
    }

    protected fun <T> ArrayList<T>.back(): String {
        return toArray().joinToOptString(separator = " ", prefix = " ")
    }

    fun decl(): String {
        return "${declPrefix.front()}$returnType $arguments${commonPostfix.back()}${declPostfix.back()}" + isAbstract.condstr { " = 0" } + ";"
    }

    fun def(): String {
        return "$returnType $scope::$arguments${commonPostfix.back()}"
    }

    fun const(): Signature {
        return this.also {
            commonPostfix.add("const")
        }
    }

    fun override(): Signature {
        return this.also {
            declPostfix.add("override")
        }
    }

    fun static(): Signature {
        return this.also {
            declPrefix.add("static")
        }
    }

    fun abstract(decl: Declaration? = null): Signature {
        return this.also {
            isAbstract = true
            declPrefix.add("virtual")
            decl?.base?.let {
                override()
            }
        }
    }
}

fun StringBuilder.appendDefaultInitialize(member: Member, typeName: String) {
    if (member is Member.Field && (member.isOptional || member.defaultValue != null)) {
        append("{")
        val defaultValue = member.defaultValue
        when (defaultValue) {
            is String -> append(if (member.type is Enum) "$typeName::$defaultValue" else "\"$defaultValue\"")
            is Long, is Boolean -> append(defaultValue)
//            else -> if (member.isOptional) append("tl::nullopt")
        }
        append("}")
    }
}

/*please set VsWarningsDefault to null if you don't need disabling VS warnings
val VsWarningsDefault : IntArray? = null*/
val VsWarningsDefault: IntArray? = intArrayOf(4250)

open class Cpp17Generator(val flowTransform: FlowTransform, val defaultNamespace: String, override val folder: File) : GeneratorBase() {

    //region language specific properties
    object Namespace : ISetting<String, Declaration>

    val Declaration.namespace: String get() = getSetting(Namespace) ?: defaultNamespace

    object Intrinsic : SettingWithDefault<CppIntrinsicMarshaller, Declaration>(CppIntrinsicMarshaller.default)

    object PublicCtors : ISetting<Unit, Declaration>

    object FsPath : ISetting<(Cpp17Generator) -> File, Toplevel>

    protected fun Declaration.fsName(isDefinition: Boolean) =
            "$name.${if (isDefinition) "cpp" else "h"}"

    protected open fun Declaration.fsPath(isDefinition: Boolean): File = getSetting(FsPath)?.invoke(this@Cpp17Generator)
            ?: File(folder, fsName(isDefinition))
    //endregion

    protected fun String.wrapper(): String {
        return "rd::Wrapper<$this>"
    }

    //endregion

    //region PrettyPrinter

    fun String.include(extension: String = "h"): String {
        return """#include "${this}.$extension""""
    }

    fun Declaration.include(): String {
        return this.name.include()
    }

    fun IType.include(): String {
        return this.name.include()
    }

    fun PrettyPrinter.carry(): String {
        return ";" + eolKind.value
    }

    protected fun PrettyPrinter.block(prefix: String, postfix: String, body: PrettyPrinter.() -> Unit) {
        +prefix
        indent(body)
        +postfix
    }

    protected fun PrettyPrinter.braceBlock(body: PrettyPrinter.() -> Unit) {
        +"{"
        indent(body)
        +"}"
    }

    protected fun PrettyPrinter.titledBlock(title: String, body: PrettyPrinter.() -> Unit) {
        +"$title {"
        indent(body)
        +"};"
    }

    protected fun PrettyPrinter.comment(comment: String) {
        +"${eolKind.value}//$comment"
    }

    protected fun PrettyPrinter.declare(signature: Signature?) {
        signature?.let {
            this.println(it.decl())
        }
    }

    protected fun PrettyPrinter.define(signature: Signature?, body: PrettyPrinter.() -> Unit) {
        signature?.let {
            this.println(it.def())
            braceBlock {
                this.body()
            }
        }
    }

    protected fun PrettyPrinter.private() {
        +"private:"
    }

    protected fun PrettyPrinter.public() {
        +"public:"
    }
    //endregion

    //region IType.
    fun IType.isPrimitive() = this in PredefinedFloating || this in PredefinedIntegrals

    fun IType.isAbstract0() = (this is Struct.Abstract || this is Class.Abstract)
    fun IType.isAbstract() = (this.isAbstract0()) || (this is InternedScalar && (this.itemType.isAbstract0()))

    fun Member.Field.isAbstract() = this.type.isAbstract()

    fun IType.substitutedName(scope: Declaration, rawType: Boolean = false, omitNullability: Boolean = false): String = when (this) {
        is Declaration -> {
            if (rawType) {
                name
            } else {
                if (isAbstract) {
                    name.wrapper()
                } else {
                    name
                }
            }
        }
        is INullable -> {
            if (omitNullability) {
                itemType.substitutedName(scope, rawType)
            } else {
                if (itemType.isAbstract()) {
                    itemType.substitutedName(scope, true).wrapper()
                } else {
                    "tl::optional<${itemType.substitutedName(scope, true)}>"
                }
            }
        }
        is InternedScalar -> itemType.substitutedName(scope, rawType)
        is IArray -> "std::vector<${itemType.substitutedName(scope, false)}>"
        is IImmutableList -> "std::vector<${itemType.substitutedName(scope, false)}>"

        is PredefinedType.byte -> "signed char"
        is PredefinedType.char -> "wchar_t"
        is PredefinedType.int -> "int32_t"
        is PredefinedType.long -> "int64_t"
        is PredefinedType.string -> "std::wstring"
        is PredefinedType.dateTime -> "Date"
        is PredefinedType.guid -> "UUID"
        is PredefinedType.uri -> "URI"
        is PredefinedType.secureString -> "RdSecureString"
        is PredefinedType.void -> "void*"
        is PredefinedType -> name.decapitalize()

        else -> fail("Unsupported type ${javaClass.simpleName}")
    }

    fun IType.templateName(scope: Declaration, omitNullability: Boolean = false) = substitutedName(scope, true, omitNullability)

    protected val IType.isPrimitivesArray
        get() = (this is IArray || this is IImmutableList) && this.isPrimitive()

    protected fun IType.leafSerializerRef(scope: Declaration): String? {
        return "rd::" + when (this) {
            is Enum -> "Polymorphic<${sanitizedName(scope)}>"
            is PredefinedType -> "Polymorphic<${substitutedName(scope)}>"
            is Declaration ->
                this.getSetting(Intrinsic)?.marshallerObjectFqn ?: run {
                    if (isAbstract) {
                        "AbstractPolymorphic<${sanitizedName(scope)}>"
                    } else {
                        "Polymorphic<${sanitizedName(scope)}>"
                    }
                }

            is IArray -> if (this.isPrimitivesArray) "Polymorphic<${substitutedName(scope)}>" else null
            else -> null
        }
    }

    protected fun IType.serializerRef(scope: Declaration): String = leafSerializerRef(scope)
            ?: "${scope.name}::__${name}Serializer"

    protected fun IType.serializerDef(scope: Declaration): String = "__${name}Serializer"

//endregion

    //region Member.
    val Member.Reactive.actualFlow: FlowKind get() = flowTransform.transform(flow)

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    protected open val Member.Reactive.implSimpleName: String
        get () = "rd::" + when (this) {
            is Member.Reactive.Task -> when (actualFlow) {
                Sink -> "RdEndpoint"
                Source -> "RdCall"
                Both -> "RdCall"
            }
            is Member.Reactive.Signal -> "RdSignal"
            is Member.Reactive.Stateful.Property -> "RdProperty"
            is Member.Reactive.Stateful.List -> "RdList"
            is Member.Reactive.Stateful.Set -> "RdSet"
            is Member.Reactive.Stateful.Map -> "RdMap"
            is Member.Reactive.Stateful.Extension -> fqn(this@Cpp17Generator, flowTransform)

            else -> fail("Unsupported member: $this")
        }


    protected open val Member.Reactive.ctorSimpleName: String
        get () = when (this) {
            is Member.Reactive.Stateful.Extension -> factoryFqn(this@Cpp17Generator, flowTransform)
            else -> implSimpleName
        }


    protected open fun Member.implSubstitutedName(scope: Declaration) = when (this) {
        is Member.EnumConst -> fail("Code must be unreachable for ${javaClass.simpleName}")
        is Member.Field -> type.substitutedName(scope)
        is Member.Reactive -> {
            implSimpleName + (genericParams.toList().map { it.templateName(scope) } + customSerializers(scope)).toTypedArray().joinToOptString(separator = ", ", prefix = "<", postfix = ">")
            /*val isProperty = (this is Member.Reactive.Stateful.Property)
            implSimpleName + (genericParams.toList().map
            { it.substitutedName(scope, omitNullability = isProperty) } + customSerializers(scope)).toTypedArray().joinToOptString(separator = ", ", prefix = "<", postfix = ">")*/
        }
    }

    protected open fun Member.implTemplateName(scope: Declaration) = when (this) {
        is Member.EnumConst -> fail("Code must be unreachable for ${javaClass.simpleName}")
        is Member.Field -> type.templateName(scope)
        is Member.Reactive -> {
            implSimpleName + (genericParams.toList().map { it.templateName(scope) } + customSerializers(scope)).toTypedArray().joinToOptString(separator = ", ", prefix = "<", postfix = ">")
            /*val isProperty = (this is Member.Reactive.Stateful.Property)
            implSimpleName + (genericParams.toList().map
            { it.templateName(scope, isProperty) } + customSerializers(scope)).toTypedArray().joinToOptString(separator = ", ", prefix = "<", postfix = ">")*/
        }
    }


    protected open fun Member.ctorSubstitutedName(scope: Declaration) = when (this) {
        is Member.Reactive.Stateful.Extension -> ctorSimpleName + genericParams.joinToOptString(separator = ", ", prefix = "<", postfix = ">") { it.templateName(scope) } + customSerializers(scope)
        else -> implSubstitutedName(scope)
    }


    protected open val Member.isBindable: Boolean
        get() = when (this) {
            is Member.Field -> type is IBindable
            is Member.Reactive -> true

            else -> false
        }


    protected open val Member.publicName: String get() = name
    protected open val Member.encapsulatedName: String get() = isEncapsulated.condstr { "_" } + publicName
    open val Member.isEncapsulated: Boolean get() = this is Member.Reactive

    protected fun Member.ctorParam(containing: Declaration, withSetter: Boolean): String {
        val typeName = implSubstitutedName(containing)
        return StringBuilder().also {
            it.append("$typeName $encapsulatedName")
            if (withSetter) {
                it.appendDefaultInitialize(this, typeName)
            }
        }.toString()
    }

    protected fun Member.Reactive.customSerializers(scope: Declaration): List<String> {
        return genericParams.asList().map { it.serializerRef(scope) }
    }

    protected open val Member.hasEmptyConstructor: Boolean
        get() = when (this) {
            is Member.Field -> type.hasEmptyConstructor && !emptyCtorSuppressed
            is Member.Reactive -> true

            else -> fail("Unsupported member: $this")
        }
//endregion

    //region Declaration.
    protected fun Declaration.sanitizedName(scope: Declaration): String {
        val needQualification = namespace != scope.namespace
        return needQualification.condstr { "$namespace::" } + name
    }

    protected fun Declaration.scopeResolution(): String {
        return "$name::"
    }

    protected fun Declaration.bases(withMembers: Boolean): List<String> {
        val baseName = baseNames(withMembers)
        return if (this.base == null) {
            val result = arrayListOf<String>()
            if (this !is Toplevel) {
                result.add("ISerializable" + withMembers.condstr { "()" })
            }
            baseName?.let { result.add(it) }
            result
        } else {
            val result = listOfNotNull(baseName).toMutableList()
            if (isUnknown(this)) {
                result.add("IUnknownInstance" + withMembers.condstr { "(std::move(unknownId))" })
            }
            result
        }
//        return listOf("ISerializable" + withMembers.condstr { "()" }) + (baseName?.let { listOf(it) } ?: emptyList())
    }

    protected fun Declaration.baseNames(withMembers: Boolean): String? {
        return this.base?.let {
            it.sanitizedName(this) + withMembers.condstr {
                "(${it.allMembers.joinToString(", ") { member -> "std::move(${member.encapsulatedName})" }})"
            }
        } ?: (
                (if (this is Toplevel) "RdExtBase"
                else if (this is Class || this is Aggregate || this is Toplevel) "RdBindableBase"
//            else if (decl is Struct) p(" : IPrintable")
                else null)?.plus(withMembers.condstr { "()" }))
    }

    val Declaration.primaryCtorVisibility: String
        get() {
            val modifier =
                    when {
                        hasSetting(PublicCtors) -> "public"
                        isAbstract -> "protected"
                        hasSecondaryCtor -> "private"
                        isExtension -> "public"
                        this is Toplevel -> "private"
                        else -> "public"
                    } + ":"
            return modifier
        }

    private val Declaration.hasSecondaryCtor: Boolean get () = (this is Toplevel || this.isConcrete) && this.allMembers.any { it.hasEmptyConstructor }
//endregion

    override fun generate(root: Root, clearFolderIfExists: Boolean, toplevels: List<Toplevel>) {
        prepareGenerationFolder(folder, clearFolderIfExists)

        val fileNames = arrayListOf<String>()

        toplevels.sortedBy { it.name }.forEach { tl ->
            val types = tl.declaredTypes + tl + unknowns(tl.declaredTypes)
            for (type in types) {
                listOf(false, true).forEach { isDefinition ->
                    type.fsPath(isDefinition).run {
                        fileNames.add(type.fsName(isDefinition))
                        bufferedWriter().use { writer ->
                            PrettyPrinter().apply {
                                eolKind = Eol.osSpecified
                                step = 4

                                //actual generation

                                if (isDefinition) {
                                    if (type !is Enum) {
                                        source(type, types)
                                    }
                                } else {
                                    header(type)
                                }

                                writer.write(toString())
                            }
                        }
                    }
                }

            }


        }

        File(folder, "CMakeLists.txt").printWriter().use {
            it.println("cmake_minimum_required(VERSION 3.10)")
            it.println("add_library(cpp_model STATIC ${fileNames.joinToString(separator = eol)})")
            it.println("target_link_libraries(cpp_model rd_framework_cpp)")
            it.println("target_include_directories(cpp_model PUBLIC \${CMAKE_CURRENT_SOURCE_DIR})")
        }
    }


    //region files
    fun PrettyPrinter.header(decl: Declaration) {
        +"#ifndef ${decl.name}_H"
        +"#define ${decl.name}_H"
        println()

        includesDecl(decl)
        println()

        dependenciesDecl(decl)
        println()

        if (decl !is Enum) {
            VsWarningsDefault?.let {
                +"#pragma warning( push )"
                it.forEach {
                    +"#pragma warning( disable:$it )"
                }
            }
        }

        if (decl is Toplevel && decl.isLibrary) {
            comment("library")
            libdecl(decl)
        } else {
            typedecl(decl)
        }
        println()

        +"#endif // ${decl.name}_H"
    }

    fun PrettyPrinter.source(decl: Declaration, dependencies: List<Declaration>) {
        +decl.include()

        println()
        if (decl is Toplevel) {
            dependencies.filter { !it.isAbstract }.filterIsInstance<IType>().println {
                it.include()
            }
        }
        println()
        if (decl is Toplevel) {
            println("""#include "${decl.root.sanitizedName(decl)}.h"""")
        }
        if (decl.isAbstract) {
            +(unknown(decl)!!.include())
        }
        if (decl is Toplevel && decl.isLibrary) {
            libdef(decl, dependencies)
        } else {
            typedef(decl)
        }
    }
//endregion

    //region declaration
    protected open fun PrettyPrinter.libdecl(decl: Declaration) {
        if (decl.getSetting(Cpp17Generator.Intrinsic) != null) return
        titledBlock("class ${decl.name}") {
            registerSerializersTraitDecl(decl)
        }
    }

    protected open fun PrettyPrinter.typedecl(decl: Declaration) {
        if (decl.getSetting(Cpp17Generator.Intrinsic) != null) return

//        println()
//        println()

        if (decl.documentation != null || decl.ownMembers.any { !it.isEncapsulated && it.documentation != null }) {
            +"/**"
            if (decl.documentation != null) {
                +" * ${decl.documentation}"
            }
            for (member in decl.ownMembers.filter { !it.isEncapsulated && it.documentation != null }) {
                +" * @property ${member.name} ${member.documentation}"
            }
            +" */"
        }

        if (decl is Enum) {
            enum(decl)
            return
        }

        if (decl.isAbstract) comment("abstract")
        if (decl is Struct.Concrete && decl.base == null) comment("data")


        p("class ${decl.name} ")
        baseClassTraitDecl(decl)
        block("{", "};") {
            comment("companion")
            companionTraitDecl(decl)

            if (decl.isExtension) {
                comment("extension")
                declare(extensionTraitDecl(decl as Ext))
            }

            comment("custom serializers")
            customSerializersTrait(decl)

            comment("fields")
            +"protected:"
            fieldsDecl(decl)

            comment("initializer")
            private()
            declare(initializerTraitDecl(decl))

            comment("primary ctor")
//            +(decl.primaryCtorVisibility)
            public()
            p("explicit ")
            primaryCtorTraitDecl(decl)
            +carry()

            comment("default ctors and dtors")
            defaultCtorsDtors(decl)

            comment("reader")
            declare(readerTraitDecl(decl))

            comment("writer")
            declare(writerTraitDecl(decl))

            comment("virtual init")
            declare(virtualInitTraitDecl(decl))

            comment("identify")
            declare(identifyTraitDecl(decl))

            comment("getters")
            gettersTraitDecl(decl)

            comment("intern")
            declare(internTraitDecl(decl))

            comment("equals trait")
            private()
            declare(equalsTraitDecl(decl))

            comment("equality operators")
            public()
            equalityOperatorsDecl(decl)

            comment("hash code trait")
            declare(hashCodeTraitDecl(decl))
//            comment("pretty print")
//            prettyPrintTrait(decl)


            /*if (decl.isExtension) {
                extensionTraitDef(decl as Ext)
            }*/
        }

        VsWarningsDefault?.let {
            println()
            +"#pragma warning( pop )"
            println()
        }

        comment("hash code trait")
        hashSpecialization(decl)
    }

    protected open fun PrettyPrinter.enum(decl: Enum) {
        titledBlock("enum class ${decl.name}") {
            +decl.constants.joinToString(separator = ",${eolKind.value}") {
                docComment(it.documentation) + it.name
            }
        }
    }

    protected fun primaryCtorParams(decl: Declaration): String {
        val own = decl.ownMembers.map {
            it.ctorParam(decl, false)
        }
        val base = decl.membersOfBaseClasses.map { it.ctorParam(decl, false) }

        return own.asSequence().plus(base).plus(unknownMembers(decl)).joinToString(", ")
    }
//endregion

    //region TraitDecl
    protected fun PrettyPrinter.includesDecl(decl: Declaration) {
//        +"class ${decl.name};"

        val standardHeaders = listOf(
                "iostream",
                "cstring",
                "cstdint",
                "vector",
                "type_traits",
                "utility",
                "typeinfo"
        )


        val frameworkHeaders = listOf(
                //root
                "Buffer",
                "Identities",
                "MessageBroker",
                "Protocol",
                "RdId",
                //impl
                "RdList",
                "RdMap",
                "RdProperty",
                "RdSet",
                "RdSignal",
                "RName",
                //serialization
                "ISerializable",
                "Polymorphic",
                "NullableSerializer",
                "ArraySerializer",
                "SerializationCtx",
                "Serializers",
                "ISerializersOwner",
                "IUnknownInstance",
                //ext
                "RdExtBase",
                //task
                "RdCall",
                "RdEndpoint",
                "RdTask",
                "RdTaskResult",
                //gen
                "gen_util"
        )

        +frameworkHeaders.joinToString(separator = eol) { s -> s.include() }
        println()
        +standardHeaders.joinToString(separator = eolKind.value, transform = { "#include <$it>" })
        println()
        //third-party
        +"optional".include("hpp")
    }

    fun PrettyPrinter.dependenciesDecl(decl: Declaration) {
        fun parseType(type: IType): ArrayList<String> {
            return when (type) {
                is IArray -> {
                    parseType(type.itemType)
                }
                is IImmutableList -> {
                    parseType(type.itemType)
                }
                is INullable -> {
                    parseType(type.itemType)
                }
                is InternedScalar -> {
                    parseType(type.itemType)
                }
                !is PredefinedType -> {
                    arrayListOf(type.name)
                }
                else -> {
                    arrayListOf()
                }
            }
        }

        fun parseMember(member: Member): ArrayList<String> {
            return when (member) {
                is Member.EnumConst -> {
                    arrayListOf()
                }
                is Member.Field -> {
                    parseType(member.type)
                }
                is Member.Reactive -> {
                    if (member is Member.Reactive.Stateful.Extension) {
                        arrayListOf(member.implSimpleName)
                    } else {
                        member.genericParams.fold(arrayListOf()) { acc, iType ->
                            acc += parseType(iType)
                            acc
                        }
                    }
                }
            }
        }

        val extHeader = listOfNotNull(if (decl.isExtension) decl.pointcut?.name else null)
        decl.ownMembers.map { parseMember(it) }.fold(arrayListOf<String>()) { acc, arrayList ->
            acc += arrayList
            acc
        }.plus(listOfNotNull(decl.base?.name)).plus(extHeader)
//                .filter { dependencies.map { it.name }.contains(it) }
                .distinct()
                .printlnWithBlankLine { it.include() }
    }

    fun PrettyPrinter.baseClassTraitDecl(decl: Declaration) {
        +decl.bases(false).joinToString(separator = ", ", prefix = ": ") { "public $it" }
    }


    protected fun createMethodTraitDecl(decl: Toplevel): Signature? {
        if (decl.isExtension) return null
        return Signature("void", "connect(Lifetime lifetime, IProtocol * protocol)", decl.name)
    }

    fun PrettyPrinter.customSerializersTrait(decl: Declaration) {
        fun IType.serializerBuilder(): String = leafSerializerRef(decl) ?: when (this) {
            is IArray -> "ArraySerializer<${itemType.serializerBuilder()}>"
            is IImmutableList -> "ArraySerializer<${itemType.serializerBuilder()}>"
            is INullable -> "NullableSerializer<${itemType.serializerBuilder()}>"
            is InternedScalar -> "InternedSerializer<${itemType.serializerBuilder()}>"
            else -> fail("Unknown type: $this")
        }

        private()
        val allTypesForDelegation = decl.allMembers
                .filterIsInstance<Member.Reactive>()
                .flatMap { it.genericParams.toList() }
                .distinct()
                .filter { it.leafSerializerRef(decl) == null }

        allTypesForDelegation.println { "using ${it.serializerDef(decl)} = ${it.serializerBuilder()};" }
    }


    protected fun PrettyPrinter.registerSerializersTraitDecl(decl: Declaration) {
        val serializersOwnerImplName = "${decl.name}SerializersOwner"
        public()
        block("struct $serializersOwnerImplName : public rd::ISerializersOwner {", "};") {
            declare(Signature("void", "registerSerializersCore(rd::Serializers const& serializers)", decl.name))
        }
        println()
        +"static $serializersOwnerImplName serializersOwner;"
        println()
    }

    protected fun PrettyPrinter.companionTraitDecl(decl: Declaration) {
        if (decl.isAbstract) {
            println()
//            abstractDeclarationTrait(decl)
            customSerializersTrait(decl)
        }
        if (decl is Toplevel) {
            println()
            registerSerializersTraitDecl(decl)
            println()
            public()
            declare(createMethodTraitDecl(decl))
            println()
        }
    }


    protected fun extensionTraitDecl(decl: Ext): Signature? {
        val pointcut = decl.pointcut ?: return null
        val lowerName = decl.name.decapitalize()
        val extName = decl.extName ?: lowerName
        return Signature("""${decl.name} const &""", "getOrCreateExtensionOf(${pointcut.sanitizedName(decl)} & pointcut)", decl.name).static()
    }

    fun PrettyPrinter.fieldsDecl(decl: Declaration) {
        val own = decl.ownMembers.map {
            val initial = getDefaultValue(decl, it)?.let {
                "{$it}"
            } ?: ""
            "${it.ctorParam(decl, true)}$initial"
        }

        +own.asSequence().plus(unknownMembers(decl)).joinToString(separator = "") { "$it${carry()}" }

        if (decl is Class && decl.isInternRoot) {
            +"mutable tl::optional<rd::SerializationCtx> mySerializationContext;"
        }
    }

    fun initializerTraitDecl(decl: Declaration): Signature {
        return Signature("void", "initialize()", decl.name)
    }

    fun PrettyPrinter.primaryCtorTraitDecl(decl: Declaration) {
//        if (decl.ownMembers.isEmpty()) return false
        p("${decl.name}(${primaryCtorParams(decl)})")
    }

    fun PrettyPrinter.defaultCtorsDtors(decl: Declaration) {
        val name = decl.name
        println()
        if (primaryCtorParams(decl).isNotEmpty()) {
            titledBlock("$name()") {
                +"initialize();"
            }
        }
        if (decl is IScalar) {
            println()
            +"$name($name const &) = default;"
            println()
            +"$name& operator=($name const &) = default;"
        }
        println()
        if (decl is Toplevel) {
            +"$name($name &&) = delete;"
            println()
            +"$name& operator=($name &&) = delete;"
        } else {
            +"$name($name &&) = default;"
            println()
            +"$name& operator=($name &&) = default;"
        }
        println()
        +"virtual ~$name() = default;"
    }

    protected fun readerTraitDecl(decl: Declaration): Signature? {
        return if (decl.isConcrete) {
            Signature(decl.name, "read(rd::SerializationCtx const& ctx, rd::Buffer const & buffer)", decl.name).static()
        } else if (decl.isAbstract) {
            Signature(decl.name.wrapper(), "readUnknownInstance(rd::SerializationCtx const& ctx, rd::Buffer const & buffer, rd::RdId const& unknownId, int32_t size)", decl.name).static()
        } else {
            null
        }
    }

    protected fun writerTraitDecl(decl: Declaration): Signature? {
        val signature = Signature("void", "write(rd::SerializationCtx const& ctx, rd::Buffer const& buffer)", decl.name).const()
        return if (decl.isConcrete) {
            signature.override()
        } else {
//            signature.abstract()
            null
        }
    }

    protected fun virtualInitTraitDecl(decl: Declaration): Signature? {
        if (decl !is BindableDeclaration) {
            return null
        }
        return Signature("void", "init(Lifetime lifetime)", decl.name).const().override()
    }

    protected fun identifyTraitDecl(decl: Declaration): Signature? {
        if (decl !is BindableDeclaration) {
            return null
        }
        return Signature("void", "identify(const rd::IIdentities &identities, rd::RdId const &id)", decl.name).const().override()
    }

    protected fun PrettyPrinter.gettersTraitDecl(decl: Declaration) {
        for (member in decl.ownMembers) {
            p(docComment(member.documentation))
            declare(Signature("${member.implTemplateName(decl)} const &", "get_${member.publicName}()", decl.name).const())
        }
    }

    protected fun PrettyPrinter.internTraitDecl(decl: Declaration): Signature? {
        return if (decl is Class && decl.isInternRoot) {
            return Signature("const rd::SerializationCtx &", "get_serialization_context()", decl.name).const().override()
        } else {
            null
        }
    }

    protected fun equalsTraitDecl(decl: Declaration): Signature {
//        val signature = Signature("bool", "equals(${decl.name} const& other)", decl.name).const()
        val signature = Signature("bool", "equals(rd::ISerializable const& object)", decl.name).const()
        return if (decl.isAbstract) {
            signature.abstract(decl)
        } else {
            if (decl.base == null) {
                signature
            } else {
                signature.override()
            }
        }
    }

    protected fun PrettyPrinter.equalityOperatorsDecl(decl: Declaration) {
//        if (decl.isAbstract || decl !is IScalar) return

        +("friend bool operator==(const ${decl.name} &lhs, const ${decl.name} &rhs);")
        +("friend bool operator!=(const ${decl.name} &lhs, const ${decl.name} &rhs);")
    }

    protected fun hashCodeTraitDecl(decl: Declaration): Signature? {
        if (decl !is IScalar) return null

        val signature = Signature("size_t", "hashCode()", decl.name).const()
        return if (decl.isAbstract) {
            signature.abstract(decl)
        } else {
            if (decl.base == null) {
                signature
            } else {
                signature.override()
            }
        }
    }

    protected fun PrettyPrinter.hashSpecialization(decl: Declaration) {
        if (decl !is IScalar) return

        block("namespace std {", "}") {
            block("template <> struct hash<${decl.name}> {", "};") {
                block("size_t operator()(const ${decl.name} & value) const {", "}") {
                    +"return value.hashCode();"
                }
            }
        }
    }

//endregion

    //region definition
    protected open fun PrettyPrinter.libdef(decl: Toplevel, types: List<Declaration>) {
        if (decl.getSetting(Cpp17Generator.Intrinsic) != null) return
        registerSerializersTraitDef(decl, types)
    }

    protected fun PrettyPrinter.typedef(decl: Declaration) {
        comment("companion")
        companionTraitDef(decl)

        if (decl.isExtension) {
            comment("extension")
            extensionTraitDef(decl as Ext)
        }

        comment("initializer")
        initializerTraitDef(decl)

        comment("primary ctor")
        primaryCtorTraitDef(decl)

        comment("reader")
        readerTraitDef(decl)

        comment("writer")
        writerTraitDef(decl)

        comment("virtual init")
        virtualInitTraitDef(decl)

        comment("identify")
        identifyTraitDef(decl)

        comment("getters")
        gettersTraitDef(decl)

        comment("intern")
        internTraitDef(decl)

        comment("equals trait")
        equalsTraitDef(decl)

        comment("equality operators")
        equalityOperatorsDef(decl)

        comment("hash code trait")
        hashCodeTraitDef(decl)
//        comment("pretty print")
//            prettyPrintTrait(decl)

    }

    protected fun PrettyPrinter.memberInitialize(decl: Declaration) {
        val result = (decl.bases(true) + decl.ownMembers.asSequence().map {
            "${it.encapsulatedName}(std::move(${it.encapsulatedName}))"
        }).toMutableList()
        if (isUnknown(decl)) {
            result += ("unknownBytes(std::move(unknownBytes))")
        }
        p(result.joinToString(separator = ", ", prefix = ": "))
    }

    protected fun PrettyPrinter.readerBodyTrait(decl: Declaration) {
        fun IType.reader(): String = when (this) {
            is Enum -> "buffer.readEnum<${substitutedName(decl)}>()"
            is InternedScalar -> {
                val lambda = lambda("SerializationCtx const &, rd::Buffer const &", "return ${itemType.reader()}")
                "ctx.readInterned<${itemType.substitutedName(decl)}>(buffer, $lambda)"
            }
            is PredefinedType.void -> "nullptr" //really?
            is PredefinedType.bool -> "buffer.readBool()"
            is PredefinedType.string -> "buffer.readWString()"
            in PredefinedIntegrals -> "buffer.read_pod<${substitutedName(decl)}>()"
            in PredefinedFloating -> "buffer.read_floating_point<${substitutedName(decl)}>()"
            is PredefinedType -> "buffer.read${name.capitalize()}()"
            is Declaration ->
                this.getSetting(Intrinsic)?.marshallerObjectFqn?.let { "$it.read(ctx, buffer)" }
                        ?: if (isAbstract)
                            "ctx.serializers->readPolymorphic<${templateName(decl)}>(ctx, buffer)"
                        else
                            "${substitutedName(decl)}::read(ctx, buffer)"
            is INullable -> {
                val lambda = lambda(null, "return ${itemType.reader()}")
                """buffer.readNullable<${itemType.substitutedName(decl)}>($lambda)"""
            }
            is IArray, is IImmutableList -> { //awaiting superinterfaces' support in Kotlin
                this as IHasItemType
                if (isPrimitivesArray) {
                    "buffer.readArray<${itemType.templateName(decl)}>()"
                }//todo inner type as template
                else {
                    """buffer.readArray<${itemType.templateName(decl)}>(${lambda(null, "return ${itemType.reader()}")})"""
                }
            }
            else -> fail("Unknown declaration: $decl")
        }

        fun Member.reader(): String = when (this) {
            is Member.Field -> type.reader()
            is Member.Reactive.Stateful.Extension -> "${ctorSubstitutedName(decl)}(${delegatedBy.reader()})"
            is Member.Reactive -> {
                val params = listOf("ctx", "buffer").joinToString(", ")
                "${implSubstitutedName(decl)}::read($params)"
            }
            else -> fail("Unknown member: $this")
        }

        fun Member.valName(): String = encapsulatedName.let { it + (it == "ctx" || it == "buffer").condstr { "_" } }

        val unknown = isUnknown(decl)
        if (unknown) {
            +"int32_t objectStartPosition = buffer.get_position();"
        }
        if (decl is Class && decl.isInternRoot) {
//            +"auto ctx = ctx.withInternRootHere(false);"
            //todo return back
        }
        if (decl is Class || decl is Aggregate) {
            +"auto _id = rd::RdId::read(buffer);"
        }
        (decl.membersOfBaseClasses + decl.ownMembers).println { "auto ${it.valName()} = ${it.reader()};" }
        if (unknown) {
            +"auto unknownBytes = rd::Buffer::ByteArray(objectStartPosition + size - buffer.get_position());"
            +"buffer.readByteArrayRaw(unknownBytes);"
        }
        val ctorParams = decl.allMembers.asSequence().map { "std::move(${it.valName()})" }.plus(unknownMemberNames(decl)).joinToString(", ")
//        p("return ${decl.name}($ctorParams)${(decl is Class && decl.isInternRoot).condstr { ".apply { mySerializationContext = ctx }" }}")
        +"${decl.name} res{${ctorParams.isNotEmpty().condstr { ctorParams }}};"
        if (decl is Class || decl is Aggregate) {
            +("withId(res, _id);")
        }
        +"return res;"
    }

    fun lambda(args: String?, body: String): String {
        return "[&](${args ?: ""}) { $body; }"
    }
//endregion

//region TraitDef

    protected fun PrettyPrinter.registerSerializersTraitDef(decl: Toplevel, types: List<Declaration>) {//todo name access
        val serializersOwnerImplName = "${decl.name}SerializersOwner"
        +"${decl.name}::$serializersOwnerImplName ${decl.name}::serializersOwner;"
        println()
        +"void ${decl.name}::${decl.name}SerializersOwner::registerSerializersCore(rd::Serializers const& serializers)"
        braceBlock {
            indent {
                types.filter { !it.isAbstract }.filterIsInstance<IType>().filterNot { iType -> iType is Enum }.println {
                    "serializers.registry<${it.name}>();"
                }

                if (decl is Root) {
                    //decl.toplevels.println { it.sanitizedName(decl) + "::serializersOwner.registry(serializers);" }
                    //todo mark graph vertex
                }
            }
        }
    }

    //only for toplevel Exts
    protected fun PrettyPrinter.createMethodTraitDef(decl: Toplevel) {
        if (decl.isExtension) return

        define(createMethodTraitDecl(decl)) {
            +"${decl.root.sanitizedName(decl)}::serializersOwner.registry(protocol->serializers);"
            println()

//            +"${decl.name} res;"
            val quotedName = """"${decl.name}""""
            +"identify(*(protocol->identity), rd::RdId::Null().mix($quotedName));"
            +"bind(lifetime, protocol, $quotedName);"
//            +"return res;"
        }
    }

    protected fun PrettyPrinter.companionTraitDef(decl: Declaration) {
        if (decl is Toplevel) {
            println()
            registerSerializersTraitDef(decl, decl.declaredTypes + unknowns(decl.declaredTypes))
            println()
            createMethodTraitDef(decl)

            println()
        }
    }


    fun PrettyPrinter.primaryCtorTraitDef(decl: Declaration) {
//        if (decl.ownMembers.isEmpty()) return
        p(decl.scopeResolution())
        primaryCtorTraitDecl(decl)
        memberInitialize(decl)
        p(" { initialize(); }")
    }

    protected fun PrettyPrinter.readerTraitDef(decl: Declaration) {
        define(readerTraitDecl(decl)) {
            if (decl.isConcrete) {
                if (isUnknown(decl)) {
                    +"""throw std::logic_error("Unknown instances should not be read via serializer");"""
                } else {
                    readerBodyTrait(decl)
                }
            } else if (decl.isAbstract) {
                readerBodyTrait(unknown(decl)!!)
            }
        }
    }

    protected fun PrettyPrinter.writerTraitDef(decl: Declaration) {
        fun IType.writer(field: String): String {
            return when (this) {
                is Enum -> "buffer.writeEnum($field)"
                is InternedScalar -> {
                    val lambda = lambda("rd::SerializationCtx const &, rd::Buffer const &, ${itemType.templateName(decl)} const & internedValue", itemType.writer("internedValue"))
                    "ctx.writeInterned<${itemType.templateName(decl)}>(buffer, $field, $lambda)"
                }
                is PredefinedType.void -> "" //really?
                is PredefinedType.bool -> "buffer.writeBool($field)"
                is PredefinedType.string -> "buffer.writeWString($field)"
                in PredefinedIntegrals -> "buffer.write_pod($field)"
                in PredefinedFloating -> "buffer.write_floating_point($field)"
                is Declaration ->
                    this.getSetting(Intrinsic)?.marshallerObjectFqn?.let { "$it.write(ctx,buffer, $field)" }
                            ?: if (isAbstract) "ctx.serializers->writePolymorphic(ctx, buffer, $field)"//todo template type
                            else "$field.write(ctx, buffer)"
                is INullable -> {
                    val lambda = lambda("${itemType.templateName(decl)} const & it", itemType.writer("it"))
                    "buffer.writeNullable<${itemType.templateName(decl)}>($field, $lambda)"
                }
                is IArray, is IImmutableList -> { //awaiting superinterfaces' support in Kotlin
                    this as IHasItemType
                    if (isPrimitivesArray) {
                        "buffer.writeArray($field)"
                    } else {
                        val lambda = lambda("${itemType.substitutedName(decl)} const & it", itemType.writer("it"))
                        "buffer.writeArray<${itemType.substitutedName(decl)}>($field, $lambda)"
                    }
                }
                else -> fail("Unknown declaration: $decl")
            }
        }


        fun Member.writer(): String = when (this) {
            is Member.Field -> type.writer(encapsulatedName)
            is Member.Reactive.Stateful.Extension -> delegatedBy.writer(("$encapsulatedName.delegatedBy"))//todo
            is Member.Reactive -> "$encapsulatedName.write(ctx, buffer)"

            else -> fail("Unknown member: $this")
        }

        if (decl.isConcrete) {
            define(writerTraitDecl(decl)) {
                if (decl is Class && decl.isInternRoot) {
                    +"this->mySerializationContext = ctx.withInternRootHere(true);"
                }
                if (decl is Class || decl is Aggregate) {
                    +"this->rdid.write(buffer);"
                }
                (decl.membersOfBaseClasses + decl.ownMembers).println { member -> member.writer() + ";" }
                if (isUnknown(decl)) {
                    +"buffer.writeByteArrayRaw(unknownBytes);"
                }
            }
        } else {
            //todo ???
        }
    }

    fun PrettyPrinter.virtualInitTraitDef(decl: Declaration) {
        virtualInitTraitDecl(decl)?.let {
            define(it) {
                val base = "rd::" + (if (decl is Toplevel) "RdExtBase" else "RdBindableBase")
                +"$base::init(lifetime);"
                decl.ownMembers
                        .filter { it.isBindable }
                        .println { """bindPolymorphic(${it.encapsulatedName}, lifetime, this, "${it.name}");""" }
            }
        }
    }

    fun PrettyPrinter.identifyTraitDef(decl: Declaration) {
        identifyTraitDecl(decl)?.let {
            define(it) {
                +"rd::RdBindableBase::identify(identities, id);"
                decl.ownMembers
                        .filter { it.isBindable }
                        .println { """identifyPolymorphic(${it.encapsulatedName}, identities, id.mix(".${it.name}"));""" }
            }
        }
    }

    protected fun PrettyPrinter.gettersTraitDef(decl: Declaration) {
        for (member in decl.ownMembers/*.filter { it.isEncapsulated }*/) {
            p(docComment(member.documentation))
            val signature = Signature("${member.implTemplateName(decl)} const &", "get_${member.publicName}()", decl.name).const()
            define(signature) {
                if (member is Member.Field && member.isAbstract()) {
                    +"return *${member.encapsulatedName};"
                } else {
                    +"return ${member.encapsulatedName};"
                }
            }
        }
    }

    protected fun PrettyPrinter.internTraitDef(decl: Declaration) {
        define(internTraitDecl(decl)) {
            +"""if (mySerializationContext) {
                    |   return *mySerializationContext;
                    |} else {
                    |   throw std::invalid_argument("Attempting to get serialization context too soon for");
                    |}""".trimMargin()
        }
    }

    protected fun PrettyPrinter.initializerTraitDef(decl: Declaration) {
        define(initializerTraitDecl(decl)) {
            decl.ownMembers
                    .filterIsInstance<Member.Reactive.Stateful>()
                    .filter { it !is Member.Reactive.Stateful.Extension && it.genericParams.none { it is IBindable } }
                    .println { "${it.encapsulatedName}.optimize_nested = true;" }

            if (flowTransform == FlowTransform.Reversed) {
                decl.ownMembers
                        .filterIsInstance<Member.Reactive.Stateful.Map>()
                        .println { "${it.encapsulatedName}.master = false;" }
            }

            decl.ownMembers
                    .filterIsInstance<Member.Reactive>()
                    .filter { it.freeThreaded }
                    .println { "${it.encapsulatedName}.async = true;" }

            /*decl.ownMembers
                    .filter { it.isBindable }
                    .println { """bindable_children.emplace_back("${it.name}", &${it.encapsulatedName});""" }*/

            if (decl is Toplevel) {
                +"serializationHash = ${decl.serializationHash(IncrementalHash64()).result}L;"
            }
        }
    }


    protected fun PrettyPrinter.equalsTraitDef(decl: Declaration) {
        define(equalsTraitDecl(decl)) {
            +"auto const &other = dynamic_cast<${decl.name} const&>(object);"
            if (decl.isAbstract || decl !is IScalar) {
                +"return this == &other;"
            } else {
                +"if (this == &other) return true;"

                decl.allMembers.println { m ->
                    val f = m as? Member.Field ?: fail("Must be field but was `$m`")
                    val t = f.type as? IScalar ?: fail("Field $decl.`$m` must have scalar type but was ${f.type}")

                    if (f.usedInEquals)
                    //                    "if (${t.eq(f.encapsulatedName)}) return false"
                        "if (this->${f.encapsulatedName} != other.${f.encapsulatedName}) return false;"
                    else
                        ""
                }
                println()
                +"return true;"
            }
        }
    }

    protected fun PrettyPrinter.equalityOperatorsDef(decl: Declaration) {
        p("bool operator==(const ${decl.name} &lhs, const ${decl.name} &rhs)")
        if (decl.isAbstract || decl !is IScalar) {
            braceBlock {
                +"return &lhs == &rhs;"
            }
        } else {
            braceBlock {
                +"if (typeid(lhs) != typeid(rhs)) return false;"
                +"return lhs.equals(rhs);"
            }
        }

        p("bool operator!=(const ${decl.name} &lhs, const ${decl.name} &rhs)")
        braceBlock {
            +"return !(lhs == rhs);"
        }
    }

    protected fun PrettyPrinter.hashCodeTraitDef(decl: Declaration) {
        fun IScalar.hc(v: String): String = when (this) {
            is IArray, is IImmutableList ->
                if (isPrimitivesArray) "contentHashCode($v)"
                else "contentDeepHashCode($v)"
            is INullable -> {
                "((bool)$v) ? " + (itemType as IScalar).hc("*$v") + " : 0"
            }
            else -> {
                if (this.isAbstract()) {
                    "std::hash<${this.templateName(decl)}>()($v)"
                } else {
                    "std::hash<${this.templateName(decl)}>()($v)"
                }
            }
        }

        hashCodeTraitDecl(decl)?.let {
            define(it) {
                +"size_t __r = 0;"

                decl.allMembers.println { m: Member ->
                    val f = m as? Member.Field ?: fail("Must be field but was `$m`")
                    val t = f.type as? IScalar ?: fail("Field $decl.`$m` must have scalar type but was ${f.type}")
                    if (f.usedInEquals)
                        "__r = __r * 31 + (${t.hc("""get_${f.encapsulatedName}()""")});"
                    else
                        ""
                }

                +"return __r;"
            }
        }
    }

    protected fun PrettyPrinter.extensionTraitDef(decl: Ext) {//todo
        define(extensionTraitDecl(decl)) {
            val lowerName = decl.name.decapitalize()
            +"""return pointcut.getOrCreateExtension<${decl.name}>("$lowerName");"""
            println()
        }

    }
//endregion

    //region unknowns
    protected fun isUnknown(decl: Declaration) =
            decl is Class.Concrete && decl.isUnknown ||
                    decl is Struct.Concrete && decl.isUnknown

    protected fun unknownMembers(decl: Declaration) =
            if (isUnknown(decl)) arrayOf("rd::RdId unknownId",
                    "rd::Buffer::ByteArray unknownBytes")
            else emptyArray()

    protected fun unknownMemberNames(decl: Declaration) =
            if (isUnknown(decl)) arrayOf("unknownId",
                    "unknownBytes")
            else emptyArray()


    override fun unknown(it: Declaration): Declaration? = super.unknown(it)?.setting(PublicCtors)
//endregion

    protected fun docComment(doc: String?) = (doc != null).condstr {
        "\n" +
                "/**\n" +
                " * $doc\n" +
                " */\n"
    }

    protected fun getDefaultValue(containing: Declaration, member: Member): String? =
            when (member) {
                is Member.Reactive.Stateful.Property -> when {
                    member.defaultValue is String -> """L"${member.defaultValue}""""
                    member.defaultValue != null -> member.defaultValue.toString()
                    else -> null
                }
                is Member.Reactive.Stateful.Extension -> member.delegatedBy.sanitizedName(containing) + "()"
                else -> null
            }


    override fun toString(): String {
        return "Cpp17Generator(flowTransform=$flowTransform, defaultNamespace='$defaultNamespace', folder=${folder.canonicalPath})"
    }

    val PredefinedIntegrals = listOf(
            PredefinedType.byte,
            PredefinedType.short,
            PredefinedType.int,
            PredefinedType.long,
            PredefinedType.char,
            PredefinedType.bool
    )

    val PredefinedFloating = listOf(
            PredefinedType.float,
            PredefinedType.double
    )
}

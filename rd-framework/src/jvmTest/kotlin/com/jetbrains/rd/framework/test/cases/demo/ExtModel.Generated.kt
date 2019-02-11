@file:Suppress("PackageDirectoryMismatch", "UnusedImport", "unused", "LocalVariableName")
package org.example

import com.jetbrains.rider.framework.*
import com.jetbrains.rider.framework.base.*
import com.jetbrains.rider.framework.impl.*

import com.jetbrains.rider.util.lifetime.*
import com.jetbrains.rider.util.reactive.*
import com.jetbrains.rider.util.string.*
import com.jetbrains.rider.util.trace
import com.jetbrains.rider.util.Date
import com.jetbrains.rider.util.UUID
import com.jetbrains.rider.util.URI
import kotlin.reflect.KClass



class ExtModel private constructor(
    private val _checker: RdSignal<Unit>
) : RdExtBase() {
    //companion
    
    companion object : ISerializersOwner {
        
        override fun registerSerializersCore(serializers: ISerializers) {
        }
        
        
        
        
        const val serializationHash = 2364843396187734L
    }
    override val serializersOwner: ISerializersOwner get() = ExtModel
    override val serializationHash: Long get() = ExtModel.serializationHash
    
    //fields
    val checker: ISignal<Unit> get() = _checker
    //initializer
    init {
        bindableChildren.add("checker" to _checker)
    }
    
    //secondary constructor
    internal constructor(
    ) : this(
        RdSignal<Unit>(FrameworkMarshallers.Void)
    )
    
    //equals trait
    //hash code trait
    //pretty print
    override fun print(printer: PrettyPrinter) {
        printer.println("ExtModel (")
        printer.indent {
            print("checker = "); _checker.print(printer); println()
        }
        printer.print(")")
    }
}
val DemoModel.extModel get() = getOrCreateExtension("extModel", ::ExtModel)


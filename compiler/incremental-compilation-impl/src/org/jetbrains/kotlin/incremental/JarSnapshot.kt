/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.incremental

import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmNameResolver
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.metadata.jvm.serialization.JvmStringTable
import org.jetbrains.kotlin.name.FqName
import java.io.*

class JarSnapshot(val protos: MutableMap<FqName, ProtoData>) {

    companion object {
        fun ObjectInputStream.readStringArray(): Array<String> {
            val size = readInt()
            val stringArray = arrayOfNulls<String>(size)
            repeat(size) {
                stringArray[it] = readUTF()
            }

            return stringArray.requireNoNulls()
        }


        fun ObjectInputStream.readJarSnapshot(): JarSnapshot {
            // Format:
            // numRecords: Int
            // record {
            //   fqName
            //   isClassProtoData
            //   *for packageClassData - packageFqName
            //   protodata via JvmProtoBufUtil
            //   string array with size
            // }
            val size = readInt()
            val mutableMap = hashMapOf<FqName, ProtoData>()
            repeat(size) {
                val fqNameString = readUTF()
                val isClassProtoData = readBoolean()
                if (isClassProtoData) {
                    val fqName = FqName(fqNameString)
                    val bytes = readStringArray()
                    val strings = readStringArray()
                    val (nameResolver, classProto) = JvmProtoBufUtil.readClassDataFrom(bytes, strings)
                    mutableMap[fqName] = ClassProtoData(classProto, nameResolver)
                } else {
                    val fqName = FqName(fqNameString)
                    val packageFqName = FqName(fqNameString)
                    val bytes = readStringArray()
                    val strings = readStringArray()
                    val (nameResolver, proto) = JvmProtoBufUtil.readPackageDataFrom(bytes, strings)
                    mutableMap[fqName] = PackagePartProtoData(proto, nameResolver, packageFqName)
                }
            }
            return JarSnapshot(mutableMap)
        }

        fun ObjectOutputStream.writeStringArray(stringArray: Array<String>) {
            writeInt(stringArray.size)
            stringArray.forEach { writeUTF(it) }
        }

        fun ObjectOutputStream.writeJarSnapshot(jarSnapshot: JarSnapshot) {
            //TODO temp solution while packageProto is not fully support
            writeInt(jarSnapshot.protos.size)
            for (entry in jarSnapshot.protos) {
                writeUTF(entry.key.asString())
                val protoData = entry.value
                when (protoData) {
                    is ClassProtoData -> {
                        writeBoolean(true) //TODO until PackageProto doesn't work
                        val nameResolver = protoData.nameResolver

                            when (nameResolver) {
                                is NameResolverImpl -> {
                                    //TODO check string mapping
                                    val writeData = JvmProtoBufUtil.writeData(protoData.proto, JvmStringTable())
                                    writeStringArray(writeData)
                                    val size = nameResolver.strings.getStringCount()
                                    writeInt(size)
                                    repeat(size) {
                                        val string = nameResolver.getString(it)
                                        writeUTF(string)
                                    }
                                }
                                is JvmNameResolver -> {
                                    val writeData = JvmProtoBufUtil.writeData(protoData.proto, JvmStringTable(nameResolver))
                                    writeStringArray(writeData)
                                    writeStringArray(nameResolver.strings)

                                }
                                else -> throw IllegalStateException("Can't store name resolver")
                            }
                    }
                    is PackagePartProtoData -> {
                        writeBoolean(false)
                        writeUTF(protoData.packageFqName.asString())
                        write(protoData.proto.toByteArray())

                        val nameResolver = protoData.nameResolver
                        when (nameResolver) {
                            is JvmNameResolver -> {
                                writeStringArray(nameResolver.strings)
                            }
                            else -> throw IllegalStateException("Can't store name resolver")
                        }

                    }
                }
            }
        }

        fun write(buildInfo: JarSnapshot, file: File) {
            ObjectOutputStream(FileOutputStream(file)).use {
                it.writeJarSnapshot(buildInfo)
            }
        }

        fun read(file: File): JarSnapshot {
            ObjectInputStream(FileInputStream(file)).use {
                return it.readJarSnapshot()
            }
        }
    }
}
package com.omninode.hub.data.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Builds a parameterized List<T> adapter from a Moshi instance.
 * Usage: moshi.listAdapter(AgentCommand::class.java)
 */
fun <T> Moshi.listAdapter(type: Class<T>): com.squareup.moshi.JsonAdapter<List<T>> {
    val listType: Type = Types.newParameterizedType(List::class.java, type)
    @Suppress("UNCHECKED_CAST")
    return adapter<List<T>>(listType)
}

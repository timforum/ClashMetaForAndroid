package com.github.kr328.clash.design.preference

interface NullableTextAdapter<T> {
    fun from(value: T): String?
    fun to(text: String?): T

    companion object {
        val Port = object : NullableTextAdapter<Int?> {
            override fun from(value: Int?): String? {
                if (value == null) return null

                return if (value > 0) value.toString() else ""
            }

            override fun to(text: String?): Int? {
                if (text == null) return null

                return text.toIntOrNull() ?: 0
            }
        }

        val String = object : NullableTextAdapter<String?> {
            override fun from(value: String?): String? {
                return value
            }

            override fun to(text: String?): String? {
                return text
            }
        }

        /** Adapter for a non-nullable [String], where clearing the field means empty. */
        val Text = object : NullableTextAdapter<String> {
            override fun from(value: String): String? {
                return value
            }

            override fun to(text: String?): String {
                return text.orEmpty()
            }
        }

        /** Adapter for a non-nullable [Long] preference such as an interval in minutes. */
        val Number = object : NullableTextAdapter<Long> {
            override fun from(value: Long): String? {
                return if (value > 0) value.toString() else ""
            }

            override fun to(text: String?): Long {
                return text?.toLongOrNull() ?: 0
            }
        }
    }
}

interface TextAdapter<T> {
    fun from(value: T): String
    fun to(text: String): T

    companion object {
        val String = object : TextAdapter<String> {
            override fun from(value: String): String {
                return value
            }

            override fun to(text: String): String {
                return text
            }
        }
    }
}
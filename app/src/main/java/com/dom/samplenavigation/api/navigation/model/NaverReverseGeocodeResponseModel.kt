package com.dom.samplenavigation.api.navigation.model

import com.google.gson.annotations.SerializedName

data class NaverReverseGeocodeResponseModel(
    @SerializedName("results") val results: List<Result>,
    @SerializedName("status") val status: Status
)

data class Result(
    @SerializedName("code") val code: Code,
    @SerializedName("land") val land: Land,
    @SerializedName("name") val name: String,
    @SerializedName("region") val region: Region
)

data class Status(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("name") val name: String
)

data class Code(
    @SerializedName("id") val id: String,
    @SerializedName("mappingId") val mappingId: String,
    @SerializedName("type") val type: String
)

data class Land(
    @SerializedName("addition0") val addition0: Addition0,
    @SerializedName("addition1") val addition1: Addition0,
    @SerializedName("addition2") val addition2: Addition0,
    @SerializedName("addition3") val addition3: Addition0,
    @SerializedName("addition4") val addition4: Addition0,
    @SerializedName("coords") val coords: Coords,
    @SerializedName("name") val name: String,
    @SerializedName("number1") val number1: String,
    @SerializedName("number2") val number2: String,
    @SerializedName("type") val type: String
)

data class Region(
    @SerializedName("area0") val area0: Area0,
    @SerializedName("area1") val area1: Area1,
    @SerializedName("area2") val area2: Area0,
    @SerializedName("area3") val area3: Area0,
    @SerializedName("area4") val area4: Area0
)

data class Addition0(
    @SerializedName("type") val type: String,
    @SerializedName("value") val value: String
)

data class Coords(
    @SerializedName("center") val center: Center
)

data class Center(
    @SerializedName("crs") val crs: String,
    @SerializedName("x") val x: Double,
    @SerializedName("y") val y: Double
)

data class Area0(
    @SerializedName("coords") val coords: Coords,
    @SerializedName("name") val name: String
)

data class Area1(
    @SerializedName("alias") val alias: String,
    @SerializedName("coords") val coords: Coords,
    @SerializedName("name") val name: String
)
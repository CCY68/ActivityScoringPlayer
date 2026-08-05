package com.johnson.fitness.http.resource

enum class NetworkError(val code: Int, val message: String) {
    TIMEOUT(1, "連線逾時，請稍後再試"),
    CONNECTION_ERROR(2, "連線錯誤"),
    HTTP_ERROR(3, "伺服器回應錯誤"),
    PARSE_ERROR(4, "資料解析錯誤"),
    GOOGLE_ERROR(5, "Google伺服器異常"),
    UNKNOWN_ERROR(999, "未知錯誤"),
    NO_SHOW_ERROR(100, "不顯示錯誤")
}
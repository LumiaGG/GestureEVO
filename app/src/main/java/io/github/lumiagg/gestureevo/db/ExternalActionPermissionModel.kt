package io.github.lumiagg.gestureevo.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@Entity(
    indices = [
        Index(value = ["callerPackage", "scopeType", "scopeValue"], unique = true)
    ]
)
@JsonClass(generateAdapter = true)
data class ExternalActionPermissionModel(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    var callerPackage: String = "",
    var link: String = "",
    var scopeType: String = ScopeType.LINK.name,
    var scopeValue: String = ""
) {
    enum class ScopeType {
        ACTION,
        LINK
    }
}

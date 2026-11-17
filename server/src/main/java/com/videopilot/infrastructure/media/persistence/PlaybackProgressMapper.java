package com.videopilot.infrastructure.media.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface PlaybackProgressMapper {

    @Select("""
            SELECT owner_id, media_id, position_ms, updated_at
            FROM media_playback_progress
            WHERE owner_id = #{ownerId} AND media_id = #{mediaId}
            """)
    PlaybackProgressRow selectOwned(
            @Param("ownerId") String ownerId,
            @Param("mediaId") String mediaId);

    @Insert("""
            INSERT INTO media_playback_progress (owner_id, media_id, position_ms, updated_at)
            VALUES (#{ownerId}, #{mediaId}, #{positionMs}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                position_ms = VALUES(position_ms),
                updated_at = VALUES(updated_at)
            """)
    int upsert(
            @Param("ownerId") String ownerId,
            @Param("mediaId") String mediaId,
            @Param("positionMs") long positionMs,
            @Param("updatedAt") Instant updatedAt);
}

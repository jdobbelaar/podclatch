package com.podclatch.domain

import com.podclatch.data.db.Feed
import com.podclatch.data.network.FeedDto

fun FeedDto.toFeed() = Feed(
    id = id,
    feedUrl = feedUrl,
    title = title,
    author = author,
    description = description,
    artworkUrl = artworkUrl,
    priority = priority,
    listenOrder = listenOrder,
    lastRefreshedAt = lastRefreshedAt,
    subscribedAt = subscribedAt,
    rev = rev,
)

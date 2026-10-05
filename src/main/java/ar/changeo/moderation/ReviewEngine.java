package ar.changeo.moderation;

import java.util.Map;

public interface ReviewEngine { Map<String, Object> review(ReviewInput input); }

/** TypeScript mirrors of content-service's JSON (PostDtos.java). */
export type SectionKey = "TECH" | "FOOTBALL" | "AI";

export interface PostSummary {
  id: string;
  slug: string;
  title: string;
  summary: string | null;
  section: SectionKey;
  coverImageUrl: string | null;
  readingMinutes: number;
  publishedAt: string;
  tags: string[];
}

export interface PostDetail extends PostSummary {
  bodyHtml: string;
  updatedAt: string;
}

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

export interface SectionCount {
  section: SectionKey;
  posts: number;
}

export interface TrendingItem {
  slug: string;
  title: string;
  section: SectionKey;
  views: number;
}

export interface FeedItem {
  id: string;
  sourceName: string;
  url: string;
  title: string;
  excerpt: string | null;
  author: string | null;
  section: SectionKey;
  imageUrl: string | null;
  publishedAt: string;
}

import React, { useRef } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";

interface HorizontalScrollSectionProps {
  title: string;
  items: any[];
  renderItem: (item: any, index: number) => React.ReactNode;
}

export const HorizontalScrollSection: React.FC<HorizontalScrollSectionProps> = ({
  title,
  items,
  renderItem,
}) => {
  const scrollRef = useRef<HTMLDivElement>(null);

  const scroll = (direction: "left" | "right") => {
    if (scrollRef.current) {
      const { scrollLeft, clientWidth } = scrollRef.current;
      const scrollAmount = clientWidth * 0.75;
      scrollRef.current.scrollTo({
        left: direction === "left" ? scrollLeft - scrollAmount : scrollLeft + scrollAmount,
        behavior: "smooth",
      });
    }
  };

  if (!items || items.length === 0) return null;

  return (
    <div className="relative mb-10 group/row">
      {/* Header Row */}
      <div className="flex items-center justify-between mb-4 px-1">
        <h2 className="font-display font-extrabold text-xl md:text-2xl text-white tracking-tight flex items-center gap-2">
          {title}
          <span className="w-1.5 h-6 brand-gradient rounded-full" />
        </h2>

        {/* Scroll Control Buttons (visible on hover) */}
        <div className="hidden md:flex items-center gap-1.5 opacity-0 group-hover/row:opacity-100 transition-opacity duration-300">
          <button
            onClick={() => scroll("left")}
            className="p-1.5 rounded-lg bg-white/5 hover:bg-brand-primary hover:text-white transition-all text-gray-400"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <button
            onClick={() => scroll("right")}
            className="p-1.5 rounded-lg bg-white/5 hover:bg-brand-primary hover:text-white transition-all text-gray-400"
          >
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Row Items Scroller */}
      <div
        ref={scrollRef}
        className="flex items-stretch gap-5 overflow-x-auto no-scrollbar scroll-smooth py-1 px-1"
      >
        {items.map((item, index) => renderItem(item, index))}
      </div>
    </div>
  );
};

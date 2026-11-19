import { 
  collection, 
  doc, 
  getDocs, 
  setDoc, 
  updateDoc, 
  deleteDoc, 
  onSnapshot 
} from "firebase/firestore";
import { db } from "../firebase";
import { Notice } from "../types";

export const DEFAULT_NOTICES: Notice[] = [
  {
    id: "default_notice_uploader_announcement",
    titleBn: "📢 নোটিশ",
    titleEn: "📢 Notice",
    contentBn: `আমাদের আপলোডাররা যত দ্রুত সম্ভব নতুন এপিসোড আপলোড করার চেষ্টা করছেন। তাই মাঝে মাঝে Episode Number বা Language-সংক্রান্ত কিছু ভুল হতে পারে।\n\nএ ধরনের কোনো সমস্যা চোখে পড়লে, অনুগ্রহ করে সংশ্লিষ্ট Anime-এর নিচে কমেন্ট করে আমাদের জানান। আপনার মন্তব্যের ভিত্তিতে আমরা দ্রুত সমস্যাটি সমাধান করার চেষ্টা করব।\n\nআপনার সহযোগিতার জন্য ধন্যবাদ! ❤️`,
    contentEn: `Our uploaders work hard to upload new episodes as quickly as possible. Occasionally, there may be mistakes in the episode number or language.\n\nIf you notice any issue, please leave a comment under the corresponding anime. We will review it and fix it as soon as possible.\n\nThank you for your support!`,
    language: "all",
    priority: "high",
    status: "active",
    displayType: "popup",
    frequency: "show_once_per_day",
    isPinned: true,
    createdAt: Date.now(),
  },
];

// Seed Firestore with Default Notice if Empty
export async function seedNoticesIfEmpty(): Promise<void> {
  try {
    const noticesRef = collection(db, "notices");
    const snap = await getDocs(noticesRef);
    if (snap.empty) {
      for (const notice of DEFAULT_NOTICES) {
        await setDoc(doc(db, "notices", notice.id), notice);
      }
    }
  } catch (err) {
    console.warn("Firestore notice seed error, falling back to local defaults:", err);
  }
}

// Subscribe to Notices real-time
export function subscribeToNotices(callback: (notices: Notice[]) => void) {
  const noticesRef = collection(db, "notices");
  return onSnapshot(
    noticesRef,
    (snap) => {
      if (!snap.empty) {
        const list = snap.docs.map((d) => ({ id: d.id, ...d.data() } as Notice));
        callback(list);
      } else {
        callback(DEFAULT_NOTICES);
      }
    },
    () => callback(DEFAULT_NOTICES)
  );
}

// CRUD Operations
export async function createNotice(notice: Omit<Notice, "id" | "createdAt">): Promise<void> {
  const id = `notice_${Date.now()}`;
  const fullNotice: Notice = {
    ...notice,
    id,
    createdAt: Date.now(),
  };
  await setDoc(doc(db, "notices", id), fullNotice);
}

export async function updateNotice(id: string, updates: Partial<Notice>): Promise<void> {
  await updateDoc(doc(db, "notices", id), updates);
}

export async function deleteNotice(id: string): Promise<void> {
  await deleteDoc(doc(db, "notices", id));
}

import { initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { getFirestore } from "firebase/firestore";

const firebaseConfig = {
  apiKey: "AIzaSyByZV6abF2QUcE2d1h5nkjNpt3dux0StLY",
  authDomain: "kanri-bu.firebaseapp.com",
  projectId: "kanri-bu",
  storageBucket: "kanri-bu.firebasestorage.app",
  messagingSenderId: "725922252135",
  appId: "1:725922252135:web:33810bb8f3e88665cd9bc2",
  measurementId: "G-SZ39BXV1NY"
};

// Initialize Firebase
const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const db = getFirestore(app);
export default app;

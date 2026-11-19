import React, { useState } from "react";
import {
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  sendPasswordResetEmail,
} from "firebase/auth";
import { auth } from "../firebase";
import { useApp } from "../context/AppContext";
import { Lock, Mail, User, Film, AlertCircle, Info } from "lucide-react";

export const AuthPage: React.FC = () => {
  const { setGuestUser } = useApp();
  const [isLogin, setIsLogin] = useState(true);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const handleAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setMessage(null);
    setIsLoading(true);

    try {
      if (isLogin) {
        await signInWithEmailAndPassword(auth, email, password);
      } else {
        localStorage.setItem("signupDisplayName", displayName);
        await createUserWithEmailAndPassword(auth, email, password);
      }
    } catch (err: any) {
      console.error(err);
      setError(err.message || "Authentication failed. Try again.");
      localStorage.removeItem("signupDisplayName");
    } finally {
      setIsLoading(false);
    }
  };

  const handleForgotPassword = async () => {
    if (!email) {
      setError("Please fill in your email address first.");
      return;
    }
    setError(null);
    setMessage(null);
    setIsLoading(true);
    try {
      await sendPasswordResetEmail(auth, email);
      setMessage("Password reset instructions have been sent to your email!");
    } catch (err: any) {
      setError(err.message || "Failed to send reset email.");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-[85vh] flex items-center justify-center p-4">
      <div className="w-full max-w-md bg-[#0d0e12]/80 backdrop-blur-2xl border border-white/5 rounded-3xl p-8 flex flex-col gap-6 shadow-2xl relative overflow-hidden">
        <div className="absolute -top-32 -left-32 w-64 h-64 bg-brand-primary/20 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute -bottom-32 -right-32 w-64 h-64 bg-brand-secondary/20 rounded-full blur-3xl pointer-events-none" />

        <div className="flex flex-col items-center gap-3 text-center">
          <div className="w-12 h-12 rounded-2xl brand-gradient flex items-center justify-center shadow-lg shadow-brand-glow">
            <Film className="w-6 h-6 text-white" />
          </div>
          <div>
            <h2 className="font-display font-extrabold text-2xl text-white">
              {isLogin ? "Welcome Back" : "Create Account"}
            </h2>
            <p className="text-gray-400 text-xs mt-1">
              {isLogin
                ? "Sign in to sync your watchlist, favorites, and progress"
                : "Sign up for personalized playlists & cross-device sync"}
            </p>
          </div>
        </div>

        {error && (
          <div className="flex items-start gap-2.5 p-3 rounded-xl bg-red-500/10 border border-red-500/10 text-red-400 text-xs">
            <AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {message && (
          <div className="flex items-start gap-2.5 p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/10 text-emerald-400 text-xs">
            <Info className="w-4 h-4 mt-0.5 flex-shrink-0" />
            <span>{message}</span>
          </div>
        )}

        <form onSubmit={handleAuth} className="flex flex-col gap-4">
          {!isLogin && (
            <div className="relative">
              <User className="absolute left-4 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-gray-500" />
              <input
                type="text"
                placeholder="Full Name"
                required
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                className="w-full pl-11 pr-4 py-3.5 bg-white/5 border border-white/5 focus:border-brand-primary/35 rounded-xl text-sm text-white focus:outline-none focus:bg-white/10 transition-all"
              />
            </div>
          )}

          <div className="relative">
            <Mail className="absolute left-4 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-gray-500" />
            <input
              type="email"
              placeholder="Email Address"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full pl-11 pr-4 py-3.5 bg-white/5 border border-white/5 focus:border-brand-primary/35 rounded-xl text-sm text-white focus:outline-none focus:bg-white/10 transition-all"
            />
          </div>

          <div className="relative">
            <Lock className="absolute left-4 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-gray-500" />
            <input
              type="password"
              placeholder="Password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full pl-11 pr-4 py-3.5 bg-white/5 border border-white/5 focus:border-brand-primary/35 rounded-xl text-sm text-white focus:outline-none focus:bg-white/10 transition-all"
            />
          </div>

          {isLogin && (
            <div className="text-right">
              <button
                type="button"
                onClick={handleForgotPassword}
                className="text-xs text-brand-secondary hover:text-brand-primary hover:underline transition-colors font-medium cursor-pointer"
              >
                Forgot Password?
              </button>
            </div>
          )}

          <button
            type="submit"
            disabled={isLoading}
            className="w-full py-3.5 mt-2 rounded-xl text-center font-bold text-sm text-white brand-gradient shadow-lg shadow-brand-glow hover:opacity-95 transition-all transform active:scale-95 flex items-center justify-center cursor-pointer"
          >
            {isLoading ? "Authenticating..." : isLogin ? "Sign In" : "Register"}
          </button>
        </form>

        <div className="text-center">
          <p className="text-gray-400 text-xs">
            {isLogin ? "Don't have an account?" : "Already have an account?"}{" "}
            <button
              onClick={() => setIsLogin(!isLogin)}
              className="text-brand-primary hover:underline font-semibold ml-1 transition-all cursor-pointer"
            >
              {isLogin ? "Register Now" : "Login Here"}
            </button>
          </p>
        </div>

        <div className="border-t border-white/5 pt-4 text-center">
          <button
            onClick={() => setGuestUser()}
            className="text-[11px] text-gray-400 hover:text-white transition-colors underline font-medium cursor-pointer"
          >
            Continue as Guest (Read Only)
          </button>
        </div>
      </div>
    </div>
  );
};

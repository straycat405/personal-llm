import api from './axios'
import type { ApiResponse, LoginResponse } from '@/types'

export const signup = (username: string, password: string, email: string) =>
  api.post<ApiResponse<void>>('/auth/signup', { username, password, email })

export const login = (username: string, password: string) =>
  api.post<ApiResponse<LoginResponse>>('/auth/login', { username, password })

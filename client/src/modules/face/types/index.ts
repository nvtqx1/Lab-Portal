export type FaceConsentStatus = 'GRANTED' | 'WITHDRAWN' | 'DELETE_REQUESTED' | 'DELETED';
export type FaceProfileStatus = 'ACTIVE' | 'DISABLED' | 'DELETED';

export interface FaceConsent {
  userId: number;
  status: FaceConsentStatus;
  changedAt: string;
}

export interface FaceProfile {
  userId: number;
  status: FaceProfileStatus;
  embeddingModel: string;
  updatedAt: string;
}

export interface FaceImageRequest {
  imageBase64: string;
  contentType: 'image/jpeg' | 'image/png';
  livenessRequired: boolean;
  challengeToken?: string;
  challengeFrames?: Array<Pick<FaceImageRequest, 'imageBase64' | 'contentType'>>;
  sideImages?: Array<Pick<FaceImageRequest, 'imageBase64' | 'contentType'>>;
}

export interface FaceChallenge {
  challengeToken: string;
  action: 'TURN_LEFT' | 'TURN_RIGHT' | 'OBSERVE';
  expiresAt: number;
}

export interface FaceGuidanceResult {
  detectedFaces: number;
  singleFace: boolean;
  faceInGuide: boolean;
  facingForward: boolean;
  landmarksVisible: boolean;
  lightingGood: boolean;
  sharpnessGood: boolean;
  centerX: number | null;
  centerY: number | null;
  faceWidthRatio: number | null;
  faceHeightRatio: number | null;
  failureReason: string | null;
}

export interface FaceCheckinResult {
  bookingId: number | null;
  userId: number | null;
  studentName: string | null;
  checkedIn: boolean;
  result: string;
  confidenceScore: number | null;
  livenessScore: number | null;
  failureReason: string | null;
  checkedInAt: string | null;
}

export interface FaceCheckinCandidate {
  bookingId: number;
  userId: number;
  studentName: string | null;
  studentEmail: string;
  labId: number;
  labName: string;
  slotId: number;
  startTime: string;
  endTime: string;
  status: string;
}

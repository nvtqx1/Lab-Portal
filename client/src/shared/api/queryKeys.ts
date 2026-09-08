export const queryKeys = {
  auth: {
    userMe: ['userMe'] as const,
  },
  labs: {
    all: ['labs'] as const,
    student: ['studentLabs'] as const,
    detail: (labId: number) => ['lab', labId] as const,
    members: (labId: number) => ['labMembers', labId] as const,
    dashboardStats: (labId: number) => ['labDashboardStats', labId] as const,
  },
  applications: {
    all: ['applications'] as const,
    manager: (labId: number) => ['managerApplications', labId] as const,
    user: (userId: number) => ['applications', 'user', userId] as const,
  },
  slots: {
    byLab: (labId: number) => ['labSlots', labId] as const,
    history: (labId: number) => ['labSlots', labId, 'history'] as const,
    detail: (slotId: number) => ['slotDetail', slotId] as const,
    bookings: (slotId: number) => ['slotBookings', slotId] as const,
  },
  bookings: {
    mine: ['myBookings'] as const,
    byLab: (labId: number) => ['bookings', labId] as const,
  },
  notifications: {
    all: ['notifications'] as const,
    page: (page: number, size: number) => ['notifications', { page, size }] as const,
  },
  assistant: {
    conversations: ['assistantConversations'] as const,
    conversation: (conversationId: number | null) => ['assistantConversation', conversationId] as const,
  },
  face: {
    profiles: ['faceProfiles'] as const,
    checkinCandidates: ['faceCheckinCandidates'] as const,
    consent: (userId: number | null) => ['faceConsent', userId ?? 'me'] as const,
    profile: (userId: number | null) => ['faceProfile', userId ?? 'me'] as const,
  },
  cleaning: {
    overview: (labId: number) => ['cleaningOverview', labId] as const,
    eligible: (slotId: number) => ['eligibleCleaners', slotId] as const,
    mine: ['myCleaningTasks'] as const,
  },
  penalties: {
    mine: ['penalties', 'me'] as const,
    bySlot: (slotId: number) => ['slotPenalties', slotId] as const,
    managerComplaints: (labId: number) => ['managerComplaints', labId] as const,
  },
  research: {
    topics: (labId: number) => ['researchTopics', labId] as const,
    topicGroups: (labId: number, topicId: number) => ['topicResearchGroups', labId, topicId] as const,
    groupProjects: (groupId: number) => ['researchGroupProjects', groupId] as const,
    projects: (labId: number) => ['researchProjects', labId] as const,
    studentProjects: (labId: number) => ['studentResearchProjects', labId] as const,
    project: (projectId: number) => ['researchProject', projectId] as const,
    projectStats: (projectId: number) => ['projectStats', projectId, 'overview'] as const,
    projectTaskBoard: (projectId: number) => ['projectTaskBoard', projectId] as const,
    projectTaskBacklog: (projectId: number, page: number, size: number) =>
      ['projectTaskBacklog', projectId, { page, size }] as const,
    taskProposalsRoot: ['taskProposals'] as const,
    taskProposals: (actorId: number | null, actorScope: object, filters: object = {}) =>
      ['taskProposals', actorId, actorScope, filters] as const,
    groups: (projectId: number) => ['researchGroups', projectId] as const,
    group: (groupId: number) => ['researchGroup', groupId] as const,
    groupMembers: (groupId: number) => ['researchGroupMembers', groupId] as const,
    eligibleStudents: (labId: number) => ['researchEligibleStudents', labId] as const,
    myGroups: (labId: number) => ['myResearchGroups', labId] as const,
    milestones: (projectId: number) => ['milestones', projectId] as const,
    milestone: (milestoneId: number) => ['milestone', milestoneId] as const,
    products: (projectId: number) => ['products', projectId] as const,
    evaluations: (projectId: number) => ['evaluations', projectId] as const,
    groupMilestones: (groupId: number) => ['groupMilestones', groupId] as const,
    myGroupMilestones: (groupId: number) => ['myGroupMilestones', groupId] as const,
    groupTasks: (groupId: number) => ['groupTasks', groupId] as const,
    groupProducts: (groupId: number) => ['groupProducts', groupId] as const,
    groupEvaluations: (groupId: number) => ['groupEvaluations', groupId] as const,
    groupResearchLogs: (groupId: number, filters?: object) => {
      if (filters) {
        return ['groupResearchLogs', groupId, filters] as const;
      }
      return ['groupResearchLogs', groupId] as const;
    },
    logs: (projectId: number, filters?: object) => {
      if (filters) {
        return ['researchLogs', projectId, filters] as const;
      }
      return ['researchLogs', projectId] as const;
    },
    tasks: (milestoneId: number) => ['tasks', milestoneId] as const,
    reports: (milestoneId: number) => ['reports', 'milestone', milestoneId] as const,
    myTasks: (groupId: number) => ['myResearchTasks', groupId] as const,
    taskReports: (taskId: number) => ['reports', 'task', taskId] as const,
    groupReports: (groupId: number) => ['groupReports', groupId] as const,
    myGroupReports: (groupId: number) => ['myGroupReports', groupId] as const,
    managerReports: (labId: number) => ['managerReports', labId] as const,
    myMilestoneReports: (milestoneId: number) => ['reports', 'milestone', milestoneId, 'me'] as const,
    reportComments: (reportId: number) => ['reportComments', reportId] as const,
  },
  admin: {
    users: ['adminUsers'] as const,
    labs: ['adminLabs'] as const,
    availableManagers: ['availableManagers'] as const,
    dashboardStats: ['adminDashboardStats'] as const,
    systemConfig: ['systemConfig'] as const,
    operationalLogs: (kind: string, page: number, filters: object) => ['operationalLogs', kind, page, filters] as const,
  },
} as const;

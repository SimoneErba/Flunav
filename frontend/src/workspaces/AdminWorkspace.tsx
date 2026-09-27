import { useLocation } from 'react-router-dom';
import { useAuth } from '../context/auth.context';
import { UserManagement } from '../components/admin/UserManagement';
import { DestinationMappingManagement } from '../components/admin/DestinationMappingManagement';
import { SensorMappingManagement } from '../components/admin/SensorMappingManagement';
import { BiEntityEvents } from '../components/admin/BiEntityEvents';
import { AppHeader } from '../components/AppHeader';
import { AppNavigation } from '../components/AppNavigation';

const AdminWorkspace = () => {
    const { user } = useAuth();
    const location = useLocation();
    const canAccessUsers = user?.role === 'SUPERADMIN';
    const canAccessDestinationMappings = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
    const canAccessSensors = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
    const canAccessBi = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
    const isDestinationMappings = location.pathname === '/admin/destination-mappings';
    const isBi = location.pathname === '/admin/bi';
    const isSensors = location.pathname === '/admin/sensors';
    const title = isDestinationMappings ? 'Destination Mappings' : isSensors ? 'Sensors' : isBi ? 'BI' : 'User Management';
    
    // 1. Center: Title
    const centerContent = (
        <div className="flex items-center gap-2 text-gray-500 dark:text-gray-400">
            <span className="font-semibold text-gray-900 dark:text-white">Admin Portal</span>
            <span>/</span>
            <span>{title}</span>
        </div>
    );

    const leftActions = <AppNavigation />;

    const content = isBi ? (
        canAccessBi ? (
            <BiEntityEvents />
        ) : (
            <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-4 rounded text-yellow-800 dark:text-yellow-200">
                You do not have permission to access BI.
            </div>
        )
    ) : isSensors ? (
        canAccessSensors ? (
            <SensorMappingManagement />
        ) : (
            <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-4 rounded text-yellow-800 dark:text-yellow-200">
                You do not have permission to manage sensors.
            </div>
        )
    ) : isDestinationMappings ? (
        canAccessDestinationMappings ? (
            <DestinationMappingManagement />
        ) : (
            <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-4 rounded text-yellow-800 dark:text-yellow-200">
                You do not have permission to manage destination mappings.
            </div>
        )
    ) : canAccessUsers ? (
        <UserManagement />
    ) : (
        <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-4 rounded text-yellow-800 dark:text-yellow-200">
            You do not have permission to manage users.
        </div>
    );

    return (
        <div className="flex flex-col h-screen bg-gray-50 dark:bg-[#121212] text-gray-900 dark:text-white transition-colors duration-300">
            
            {/* UNIFIED HEADER */}
            <AppHeader centerContent={centerContent} leftActions={leftActions} />

            <div className="flex-1 overflow-y-auto p-8">
                <div className={`${isDestinationMappings || isSensors || isBi ? 'max-w-[1600px]' : 'max-w-6xl'} mx-auto space-y-8`}>
                    {content}
                </div>
            </div>
        </div>
    );
};


export default AdminWorkspace;
